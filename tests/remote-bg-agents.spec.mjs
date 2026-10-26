// remote-bg-agents.spec.mjs — 台账 #929「后台子智能体跨设备透明」前端验收
//（作者令 2026-10-26 13:51 逐字：「多设备设计的核心就是透明，跑在哪个设备上，那我们
//  的显示都应该都能看到。不应该说只显示Mac引擎自带的子智能体」）。
//
// 契约（backend = AgentSessionSync session-sync 通道收敛 + remoteAgents 帧；
// 本 spec 钉**面板注册表面**渲染钉，引擎路径由 AgentSessionSyncSpec 钉）：
//   P1 在线运行行：远端投影行入面板（合并载体 #bg-dropdown 的智能体节），状态词
//      「进行中」+ kind 徽标（Delegate）+ 设备角标（KAI，title「跑在 KAI」）+ uptime；
//   P2 状态变更实时推：completed 帧 ⇒ 行翻「已完成」，~2s 后自清（agentDone 同律），
//      同戳重发不复活（snapmerge「done 单调」同款防线）；
//   P3 断联语义：deviceOnline=false ⇒ 行保留最后态 + 琥珀「设备离线」角标（不消失
//      不假装死亡）；恢复 online=true ⇒ 角标消失；
//   P4 本地行胜：同 sessionId 已在本地桶（DeviceDelegate 双侧各一行形态）⇒ 远端
//      孪生被抑制，本地行原样；
//   P5 对照回归：本地行（sessionBgAgents + activeAgents 快照）渲染逐字不变，远端
//      行不入 per-session 徽标计数。
//
// 数据面 = WS remoteAgents 帧直种 + activeBgTasks/activeAgents 桩，零后端、零 sbt。
// Run: node tests/remote-bg-agents.spec.mjs（自包含：静态服务器随机隔离端口，finally 必关）
import { chromium } from 'playwright-core';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');

let failures = 0;
function ok(name, cond, extra = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
}
const sleep = ms => new Promise(r => setTimeout(r, ms));
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2' };

const LOCAL_SID = 'root-local-session';
const T0 = Date.now();

// ── 引擎侧可控状态（桩）────────────────────────────────────────────────
const engine = {
  // AgentSessionSync.remoteAgents 帧载荷（sendRemote(agents) 推给当前连接）
  remote: [],
};

// ── 桩 WS ──────────────────────────────────────────────────────────────
let currentWs = null;
function wireUp(ws) {
  currentWs = ws;
  ws.onMessage(raw => {
    let m; try { m = JSON.parse(raw); } catch { return; }
    if (m.type === 'ping') { ws.send(JSON.stringify({ type: 'pong' })); return; }
    if (m.type === 'getHistory') {
      ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0, total: 0 }));
      return;
    }
    if (m.type === 'getActiveAgents') {
      // 快照回复捎带腿（handleGetActiveAgents pushRemoteRowsTo 同拍）：远端行随
      // activeAgents 同发——P4/P5 刷新恢复路径的桩。
      ws.send(JSON.stringify({ type: 'activeAgents', agents: engine.snapshot || [] }));
      if (engine.remote.length) ws.send(JSON.stringify({ type: 'remoteAgents', agents: engine.remote }));
      return;
    }
  });
  ws.send(JSON.stringify({ type: 'configData', config: '{"features":{}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
  ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: LOCAL_SID, name: '本机会话' }], activeId: LOCAL_SID, folders: [] }));
}
const sendRemote = (agents) => { engine.remote = agents; currentWs.send(JSON.stringify({ type: 'remoteAgents', agents })); };

// ── 静态服务器 + 浏览器 ────────────────────────────────────────────────
const server = createServer(async (req, res) => {
  try {
    const path = decodeURIComponent(new URL(req.url, 'http://x').pathname);
    const file = normalize(join(WEB, path === '/' ? 'index.html' : path));
    const body = await readFile(file);
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
    res.end(body);
  } catch {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end('{}');
  }
});
await new Promise(r => server.listen(0, '127.0.0.1', r));
const BASE = `http://127.0.0.1:${server.address().port}`;

const browser = await chromium.launch();
let ctx = null;
try {
  ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('stand_input_history', '[]');
  });
  const page = await ctx.newPage();
  const pageErrors = [];
  page.on('pageerror', e => pageErrors.push(e.message));

  await page.route('**/api/**', r => r.fulfill({ json: {} }));
  await page.routeWebSocket(/\/ws/, ws => wireUp(ws));
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#input', { state: 'visible', timeout: 20000 });
  await sleep(1000);

  // 对照本地行（activeAgents 快照恢复路径直种——快照合并语义顺带被回归）。
  // 徽标口径（author ruling 2026-09-14）：只数运行中的本地行；远端行不入计数
  // （P5 断言的对照基线 = 本地 2 行 ⇒ 计数 2）。
  const localSnapshot = () => ([
    { sessionId: 'delegate-local-1', agentId: 'delegate-local-1', agentName: '本地批次一', rootSessionId: LOCAL_SID, kind: 'Delegate', status: 'Processing', startedAt: T0 - 20_000, retryCount: 0, project: '', taskId: '', taskTitle: '', heldReceipts: 0, unreadReceipts: 0, outstandingSubagents: 0, bgWaitingCount: 0, bgWaiting: [], currentTool: '', lastActivityMs: T0 },
  ]);
  currentWs.send(JSON.stringify({ type: 'activeAgents', agents: localSnapshot() }));
  await sleep(400);

  const openPanel = async () => {
    // 后台合并批：bgagent 键开合并载体 #bg-dropdown（幽灵面 #bgagent-dropdown 恒隐）。
    // 幂等：载体在开 = 跳过点击（再点 = toggle 收起）。
    const carrier = page.locator('#bg-dropdown');
    if (!(await carrier.isVisible().catch(() => false))) {
      await page.click('#bgagent-indicator');
      await page.waitForSelector('#bg-dropdown', { state: 'visible', timeout: 5000 });
    }
  };
  const remoteRow = (sid) => page.locator(`.bg-task-row-remote[data-remote-session="${sid}"]`);

  // ── P1: 在线运行行（设备角标 + 状态词 + kind + uptime）─────────────────────
  sendRemote([
    { sessionId: 'delegate-kai-1', title: '批次：跨设备投影', source: 'delegate', status: 'running', startedAt: T0 - 60_000, endedAt: null, updatedAt: T0, retryCount: 0, deviceId: 'kai-dev-1', deviceName: 'KAI', deviceOnline: true },
  ]);
  await sleep(400);
  {
    await openPanel();
    const row = remoteRow('delegate-kai-1');
    ok('P1 远端行入面板', await row.count() === 1);
    ok('P1 状态词「进行中」', (await row.locator('.bg-task-state').textContent()) === '进行中');
    ok('P1 kind 徽标 Delegate', (await row.locator('.bg-task-kind').textContent()) === 'Delegate');
    const dev = row.locator('.bg-task-device');
    ok('P1 设备角标 KAI', (await dev.textContent()) === 'KAI');
    ok('P1 设备角标 title「跑在 KAI」', (await dev.getAttribute('title')) === '跑在 KAI');
    ok('P1 在线 ⇒ 无离线角标', await row.locator('.bg-task-offline').count() === 0);
    ok('P1 uptime 在场（非终态）', await row.locator('.bg-task-uptime').count() === 1);
    // 展示专用：无点击展开锚（跨设备详情不在本批边界）
    ok('P1 展示专用（无点击锚）', await row.getAttribute('data-node-session-id') === null);
  }

  // ── P2: 状态变更实时推（completed ⇒ 翻终态 ⇒ 2s 自清 ⇒ 同戳重发不复活）───────
  sendRemote([
    { sessionId: 'delegate-kai-1', title: '批次：跨设备投影', source: 'delegate', status: 'completed', startedAt: T0 - 60_000, endedAt: Date.now(), updatedAt: T0 + 5_000, retryCount: 0, deviceId: 'kai-dev-1', deviceName: 'KAI', deviceOnline: true },
  ]);
  await sleep(300);
  ok('P2 终态实时翻面「已完成」', (await remoteRow('delegate-kai-1').locator('.bg-task-state').textContent()) === '已完成');
  await sleep(2300); // 2s 清理窗
  ok('P2 终态 2s 自清（agentDone 同律）', await remoteRow('delegate-kai-1').count() === 0);
  sendRemote([
    { sessionId: 'delegate-kai-1', title: '批次：跨设备投影', source: 'delegate', status: 'completed', startedAt: T0 - 60_000, endedAt: Date.now(), updatedAt: T0 + 5_000, retryCount: 0, deviceId: 'kai-dev-1', deviceName: 'KAI', deviceOnline: true },
  ]);
  await sleep(400);
  ok('P2 同戳重发不复活（done 单调防线）', await remoteRow('delegate-kai-1').count() === 0);

  // ── P3: 断联语义（离线行保留最后态 + 角标；恢复在线 ⇒ 角标消失）──────────────
  sendRemote([
    { sessionId: 'delegate-kai-2', title: '批次：断联保持', source: 'delegate', status: 'running', startedAt: T0 - 30_000, endedAt: null, updatedAt: T0, retryCount: 1, deviceId: 'kai-dev-1', deviceName: 'KAI', deviceOnline: false },
  ]);
  await sleep(400);
  {
    await openPanel();
    const row = remoteRow('delegate-kai-2');
    ok('P3 离线行保留（不消失）', await row.count() === 1);
    ok('P3 离线行保持运行态', (await row.locator('.bg-task-state').textContent()) === '进行中');
    ok('P3 「设备离线」角标在场', (await row.locator('.bg-task-offline').textContent()) === '设备离线');
    ok('P3 重试片在场 ×1', (await row.locator('.bg-task-retries').textContent()) === '×1');
    sendRemote([
      { sessionId: 'delegate-kai-2', title: '批次：断联保持', source: 'delegate', status: 'running', startedAt: T0 - 30_000, endedAt: null, updatedAt: T0 + 1_000, retryCount: 1, deviceId: 'kai-dev-1', deviceName: 'KAI', deviceOnline: true },
    ]);
    await sleep(400);
    ok('P3 重连补推 ⇒ 离线角标消失', await remoteRow('delegate-kai-2').locator('.bg-task-offline').count() === 0);
  }

  // ── P4: 本地行胜（同 sessionId 双侧各有行 ⇒ 远端孪生抑制）──────────────────
  // 先造本地行（activeAgents 快照恢复路径），再推同 sessionId 远端行。
  engine.snapshot = [
    { sessionId: 'delegate-both-1', agentId: 'delegate-both-1', agentName: '本地长名批次', rootSessionId: LOCAL_SID, kind: 'Delegate', status: 'Processing', startedAt: T0 - 10_000, retryCount: 0, project: '', taskId: '', taskTitle: '', heldReceipts: 0, unreadReceipts: 0, outstandingSubagents: 0, bgWaitingCount: 0, bgWaiting: [], currentTool: '', lastActivityMs: T0 },
    ...localSnapshot(),
  ];
  // 页面侧触发快照拉取（面板打开路径 fetchActiveAgentsOnOpen——currentWs.send 是
  // 「桩→页面」方向，请求必须由页面发起）。
  await page.evaluate(async () => {
    const m = await import('/js/main/bgAgents.js');
    m.fetchActiveAgentsOnOpen();
  });
  await sleep(700);
  sendRemote([
    { sessionId: 'delegate-both-1', title: '远端孪生批次', source: 'delegate', status: 'running', startedAt: T0, endedAt: null, updatedAt: T0, retryCount: 0, deviceId: 'kai-dev-1', deviceName: 'KAI', deviceOnline: true },
    { sessionId: 'delegate-kai-2', title: '批次：断联保持', source: 'delegate', status: 'running', startedAt: T0 - 30_000, endedAt: null, updatedAt: T0 + 1_000, retryCount: 1, deviceId: 'kai-dev-1', deviceName: 'KAI', deviceOnline: true },
  ]);
  await sleep(600);
  {
    await openPanel();
    ok('P4 远端孪生被抑制', await remoteRow('delegate-both-1').count() === 0);
    const localRow = page.locator(`.bg-task-row[data-node-session-id="delegate-both-1"]`);
    ok('P4 本地行原样在场', await localRow.count() === 1);
    ok('P4 本地行名未被远端覆写', ((await localRow.locator('.bg-task-name').getAttribute('title')) || '').includes('本地长名批次'));
  }

  // ── P5: 对照回归（远端行不入 per-session 徽标计数；本地行照常计数）────────────
  {
    const count = await page.locator('#bgagent-indicator .bgagent-count').textContent().catch(() => '');
    // 本地行 = delegate-local-1 + delegate-both-1（P4 快照后都在册）；远端行
    // （delegate-kai-2 在场）一律不入计数 ⇒ 基线 2。
    ok('P5 徽标计数不含远端行（=2 仅本地两行）', String(count).trim() === '2', `count=${count}`);
    ok('P5 本地行 + 远端行并存一板', await remoteRow('delegate-kai-2').count() === 1);
  }

  ok('页面零报错', pageErrors.length === 0, pageErrors.join(' | ').slice(0, 200));

  // ── E: 静态钉（源面接缝在场）───────────────────────────────────────────
  const { readFileSync: rfs } = await import('node:fs');
  const mainSrc = rfs(join(WEB, 'js', 'main.js'), 'utf8');
  ok('E1 remoteAgents 帧消费在源面', mainSrc.includes("onMessage('remoteAgents'"));
  ok('E2 本地行胜去重接缝在源面', mainSrc.includes('localSessionIds'));
  ok('E3 终态 2s 清理接缝在源面', mainSrc.includes('scheduleRemoteTerminalCleanup'));
  const wsSrc = rfs(join(WEB, 'js', 'ws.js'), 'utf8');
  ok('E4 remoteAgents 入 GLOBAL 路由集', wsSrc.includes("'remoteAgents'"));
  const bgSrc = rfs(join(WEB, 'js', 'main', 'bgAgents.js'), 'utf8');
  ok('E5 面板远端行渲染接缝在源面', bgSrc.includes('bgRemoteRowsHtml') && bgSrc.includes('bg-task-row-remote'));
  const cssSrc = rfs(join(WEB, 'css', 'chat.css'), 'utf8');
  ok('E6 设备/离线角标样式在源面', cssSrc.includes('.bg-task-device') && cssSrc.includes('.bg-task-offline'));
} catch (e) {
  ok('harness 异常', false, e.message);
} finally {
  await browser.close().catch(() => {});
  server.close();
}
console.log(failures === 0 ? '\nALL PASS' : `\n${failures} FAIL`);
process.exit(failures === 0 ? 0 : 1);
