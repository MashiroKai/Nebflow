// busy-lifecycle.spec.mjs — 修复批「会话 busy 态卡死」（2026-10-20）验收。
//
// 报障（作者实撞）：agent 轮次实际已结束（回复已完整落），客户端会话 busy/spinner
// 态不清除；后续消息全部进本地待发队列且永不 flush（busy 永不清 = 队列唯一的终态
// drain 挂点永不触发）。
//
// 根因（客户端 busy 生命周期误判，引擎侧零触碰）：
//   · #569 起，服务端对已受理过的 clientMessageId 丢弃不开 turn（AgentIdle /
//     AgentFrozen / AgentProcessing 三处去重点 + messageDuplicate 回执）；客户端
//     派发腿（send / drainMessageQueue / dispatchOfflineItem）写帧成功后乐观置位
//     busy——「帧写出去」从此不再蕴含「turn 会跑」= 幻影 busy。
//   · 判重回执消费面两缺口：① 15s 守卫读**全局** lastStreamActivity——多代理工作区
//     任何其他会话的流帧都给本会话幻影 busy 续命 ⇒ 回执永不收 busy；② 收了也不
//     scheduleQueueDrain ⇒ 队列停摆。无回执形态（补发重放被吞/冻结唤醒搁置）则连
//     收口都没有（看门真死分支在 WS 存活时被 pong 活性证据永久挡住 = by design）。
//
// 修法（本批）：Fix 1 = 回执升级权威无-turn 信号（clientMessageId 与本会话最新派发
// id 精确对账 + per-session 流活动对账，命中即收 busy 并照常 drain；id 对不上回落
// per-session 15s 静默窗）；Fix 2 = 乐观置位草签化（turn-start 确认网：引擎权威
// 开turn 信号 sessionBusy{busy:true}/终态帧/本会话流帧任一到场即撤，到点无 turn
// 事实 ⇒ 判幻影收 busy + drain。depth>0 前缀会话不武装）。
//
// 场景（真模块图 + routeWebSocket 桩服务端，真用户动作驱动；应答脚本 = 函数形态
// frame => steps，逐步 delay 可控时序）：
//   A 复现基线：busy 中排队 → done+sessionBusy{false} → busy 清 + 队列恰派发一条
//     （既有终态链回归钉）。
//   B 判重回执幻影（Fix 1 主钉）：派发被服务端判重丢弃（回执带真实 id + busy{false}，
//     无 turn）→ 回执到场即收 busy；B2' 另会话流帧**不再**给幻影续命（全局→
//     per-session 守卫的回归捕获器：回执延迟 150ms，50ms 时注另会话流帧——旧全局
//     守卫在此必红）；B3 本会话真流在场时回执不误杀真 busy。
//   C 无回执幻影（Fix 2 确认网，turnConfirmMs=800）：派发后服务端零回帧 → 网到点
//     收 busy + turnExpecting 一并清；C' 幻影窗口排队的消息照常 flush。
//   D 确认网不误伤真 turn（负例）：busy{true} 250ms 到场（<800ms 窗口）撤网，
//     done 拖到 2.2s —— busy 全程保留到终态。
//   E 静态钉：确认网接缝 / 回执 drain 挂点 / per-session 守卫源面在场。
//
// Run: node tests/busy-lifecycle.spec.mjs
// 自包含：静态服务器随机隔离端口 + routeWebSocket 桩，finally 必关。
import { chromium } from 'playwright-core';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const SID = 's-busy-e2e';
const OTHER = 's-other-e2e';

let failures = 0;
function ok(name, cond, extra = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
}
const sleep = ms => new Promise(r => setTimeout(r, ms));
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2' };

// ── 桩服务端状态 ────────────────────────────────────────────────────
let currentWs = null;
let connSeq = 0;
const conns = [];
// 重连丢终态场景：true = 新连接立即掐断（黑暗窗）；终态帧排程在旧连接关闭后发送 = 丢失。
let serverDown = false;
// 重连 historyPage 夹具：黑暗窗内已完成的轮（terminal 行 ⇒ turn-header 呈 done）。
let historyFixture = [];
// 每场景注入的应答脚本：frame => [{frame, delay}]（一次性：出线帧只消费一枚）。
let replyScript = null;

function isUserContentFrame(m) {
  return !m.type && typeof m.content === 'string';
}

function wireUp(ws) {
  if (serverDown) {
    try { ws.close(); } catch { /* already closed */ }
    return;
  }
  currentWs = ws;
  const log = { seq: ++connSeq, inbound: [] };
  conns.push(log);
  ws.onMessage(raw => {
    let m; try { m = JSON.parse(raw); } catch { return; }
    log.inbound.push(m);
    if (m.type === 'ping') { ws.send(JSON.stringify({ type: 'pong' })); return; }
    if (m.type === 'getHistory') {
      ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: historyFixture, hasMore: false, offset: 0, total: historyFixture.length }));
      return;
    }
    if (m.type === 'getSessionBusy') {
      // 权威快照夹具：本轮所有 requested root sessions 均已 idle。旧前端不发此请求，
      // 因而 G 场景在修复前保持幽灵 busy（红腿）。
      const states = Object.fromEntries((m.sessionIds || []).map(sid => [sid, false]));
      ws.send(JSON.stringify({ type: 'sessionBusyStates', states }));
      return;
    }
    if (isUserContentFrame(m) && replyScript) {
      const script = replyScript;
      replyScript = null; // 一次性
      for (const step of script(m)) {
        setTimeout(() => {
          try { ws.send(JSON.stringify(step.frame)); } catch { /* 连接已被测试关闭 */ }
        }, step.delay || 0);
      }
    }
  });
  // 引导帧（保持应用安静 + 挂上被测会话）
  ws.send(JSON.stringify({ type: 'configData', config: '{"features":{},"console":{"inputMode":"queue"}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
  ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'busy生命周期E2E' }], activeId: SID, folders: [] }));
}

/** 服务端注入任意帧（模拟引擎事件流）。 */
const serverSends = (frames) => {
  for (const f of frames) currentWs.send(JSON.stringify(f));
};

const inboundUserFramesOf = (content) => conns.flatMap(c => c.inbound).filter(m => isUserContentFrame(m) && m.content === content);
const sessionBusyQueries = () => conns.flatMap(c => c.inbound).filter(m => m.type === 'getSessionBusy');

async function waitForNewConnection(prevSeq, timeoutMs = 10000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const latest = conns[conns.length - 1];
    if (latest && latest.seq > prevSeq) return latest;
    await sleep(50);
  }
  throw new Error('client did not reconnect in time');
}

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
    res.end('{}'); // /api/* 探针一律 200 空对象（boot 静音）
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
  await sleep(1500); // 引导帧消化（sessionList → 视图挂载）

  const sidBusy = () => page.evaluate(async (sid) => {
    const s = await import('/js/state.js');
    return s.default.busySessionIds.has(sid);
  }, SID);
  const turnExpecting = () => page.evaluate(async (sid) => {
    const s = await import('/js/state.js');
    return !!s.default.turnExpecting[sid];
  }, SID);
  const busyUi = () => page.evaluate(() => {
    const stop = document.getElementById('stop-btn');
    const send = document.getElementById('send-btn');
    return { stopShown: !!stop && stop.style.display === 'flex', sendShown: !!send && send.style.display === 'flex' };
  });
  const sendText = async (text) => {
    await page.fill('#input', text);
    await page.keyboard.press('Enter');
  };
  const queuedItems = () => page.evaluate(() => {
    try {
      const raw = localStorage.getItem(Object.keys(localStorage).find((k) => k.includes('message_queue')));
      return Object.values(JSON.parse(raw || '{}')).flat();
    } catch { return []; }
  });
  const setConfirmMs = (ms) => page.evaluate(async (v) => {
    (await import('/js/state.js')).default.turnConfirmMs = v;
  }, ms);

  ok('boot 后在线', conns.length > 0);

  // ══ A 复现基线：busy 中排队 → 终态 → busy 清 + 队列恰派发一条 ══════
  replyScript = () => [{ frame: { type: 'sessionBusy', sessionId: SID, busy: true } }]; // 轮次挂住
  await sendText('busy msg one');
  await sleep(300);
  ok('A1 首条派发 + 服务端 busy 中', inboundUserFramesOf('busy msg one').length === 1 && (await sidBusy()));
  await sendText('queued while busy');
  await sleep(300);
  ok('A2 busy 中第二条入队（不出线）', inboundUserFramesOf('queued while busy').length === 0 && (await queuedItems()).length === 1);
  // 终态对 + drain 派发轮的完整应答（busy{true}/{false}+done——drain 开的新 turn 照
  // 常收尾，B 场景从 idle 起）。
  serverSends([
    { type: 'sessionBusy', sessionId: SID, busy: false },
    { type: 'done', sessionId: SID, model: 'mock-llm' },
  ]);
  replyScript = () => [
    { frame: { type: 'sessionBusy', sessionId: SID, busy: true } },
    { frame: { type: 'sessionBusy', sessionId: SID, busy: false }, delay: 150 },
    { frame: { type: 'done', sessionId: SID }, delay: 160 },
  ];
  await sleep(700); // 50ms drain 定时器 + drain 轮应答消化
  replyScript = null;
  ok('A3 终态后 busy 清', !(await sidBusy()));
  ok('A4 终态后队列恰派发一条（per-turn 窗口闸回归钉）', inboundUserFramesOf('queued while busy').length === 1,
    `frames=${inboundUserFramesOf('queued while busy').length}`);
  const uiA = await busyUi();
  ok('A5 composer 回到可发送态（stop 隐 / send 显）', uiA.sendShown && !uiA.stopShown, JSON.stringify(uiA));

  // ══ B 判重回执幻影（Fix 1 主钉）：派发被判重丢弃 → 回执到场即收 busy ══
  // 形态 = 服务端丢弃已受理 id：不开 turn、零流帧，回 messageDuplicate（带**真实**
  // clientMessageId = 精确腿对账）+ busy{false}。
  replyScript = (m) => [
    { frame: { type: 'messageDuplicate', sessionId: SID, clientMessageId: m.clientMessageId }, delay: 40 },
    { frame: { type: 'sessionBusy', sessionId: SID, busy: false }, delay: 45 },
  ];
  await sendText('phantom dedup send');
  await sleep(400);
  ok('B1 幻影派发出线恰一帧', inboundUserFramesOf('phantom dedup send').length === 1);
  ok('B2 判重回执到场即收 busy（精确腿，无 15s 等待）', !(await sidBusy()), `busy=${await sidBusy()}`);
  ok('B3 turnExpecting 同步收掉', !(await turnExpecting()));
  ok('B4 幻影派发单条用户气泡（重发零新增呈现不受影响）', await page.evaluate((t) =>
    [...document.querySelectorAll('.row.user .bubble.user')].filter(b => (b.textContent || '').includes(t)).length === 1, 'phantom dedup send'));

  // B2' 全局→per-session 守卫的回归捕获器：另会话流帧在场 + 回执延迟 150ms。
  // 旧全局守卫：50ms 的另会话流帧把全局 lastStreamActivity 刷新 ⇒ 15s 窗内回执
  // 永不收 busy（本断言在旧实现必红）。新 per-session 精确腿：照收。
  replyScript = (m) => [
    { frame: { type: 'messageDuplicate', sessionId: SID, clientMessageId: m.clientMessageId }, delay: 150 },
  ];
  await sendText('phantom with other-session stream');
  await sleep(60); // 帧出线（~0ms）→ 50ms 注另会话流帧（回执 150ms 才到）
  serverSends([{ type: 'textDelta', sessionId: OTHER, delta: 'other session is streaming' }]);
  await sleep(500);
  ok('B2a 另会话流帧不再给本会话幻影续命（per-session 守卫）', !(await sidBusy()), `busy=${await sidBusy()}`);

  // B2b 精确腿专属探针：本会话在派发**前** 15s 内有流活动（上一轮尾流）——
  // 旧全局/15s 回落守卫在此必红（sinceSidStream < 15000 ⇒ 回执被挡，幻影坐满
  // 15s）；精确腿按 id 对账不受前置流活动干扰，回执到场即收。
  serverSends([
    { type: 'textDelta', sessionId: SID, delta: 'prev turn tail stream' },
    { type: 'sessionBusy', sessionId: SID, busy: false }, // 流戳留下、busy 不留（textDelta 会 arm busy）
  ]);
  await sleep(200);
  replyScript = (m) => [
    { frame: { type: 'messageDuplicate', sessionId: SID, clientMessageId: m.clientMessageId }, delay: 40 },
  ];
  await sendText('phantom right after live stream');
  await sleep(400);
  ok('B2b 派发前 15s 内有本会话流活动 ⇒ 仍走精确腿即收（回落 15s 窗会挡死）', !(await sidBusy()), `busy=${await sidBusy()}`);

  // B3 本会话真流在场：陈旧回执不误杀真 turn 的 busy（busy 属于流中的 turn）
  serverSends([
    { type: 'sessionBusy', sessionId: SID, busy: true },
    { type: 'textDelta', sessionId: SID, delta: 'live turn streaming' },
    { type: 'messageDuplicate', sessionId: SID, clientMessageId: 'stale-old-id' },
  ]);
  await sleep(300);
  ok('B3a 陈旧回执 + 本会话真流在场 ⇒ busy 保留', await sidBusy(), `busy=${await sidBusy()}`);
  serverSends([{ type: 'sessionBusy', sessionId: SID, busy: false }, { type: 'done', sessionId: SID }]);
  await sleep(200);
  ok('B3b 真流终态照常收 busy', !(await sidBusy()));

  // ══ C 无回执幻影（Fix 2 确认网）：派发后服务端零回帧 → 网到点收 busy ══════
  await setConfirmMs(800);
  await sendText('silent drop send'); // 桩无脚本 = 零回帧（无 busy{true}/流帧/回执/终态）
  await sleep(300);
  ok('C1 派发后短窗内 busy 置位（乐观草签在场）', await sidBusy());
  await sleep(900); // 确认网到点（800ms）
  ok('C2 确认网到点收幻影 busy（无任何服务端帧）', !(await sidBusy()), `busy=${await sidBusy()}`);
  ok('C3 幻影收口连 turnExpecting 一并清', !(await turnExpecting()));

  // C' 幻影窗口排队的消息照常 flush：真 busy 挂住 → 排队一条 → 终态 → drain 派发轮
  // 自带完整应答（确认网被其 busy{true} 正常撤网 = flush 链不回归）。
  replyScript = () => [{ frame: { type: 'sessionBusy', sessionId: SID, busy: true } }];
  await sendText('pre-queue hold');
  await sleep(300);
  await sendText('queued during phantom window');
  await sleep(300);
  ok('C4 队列项在场（busy 中入队）', inboundUserFramesOf('queued during phantom window').length === 0 && (await queuedItems()).length === 1);
  serverSends([{ type: 'sessionBusy', sessionId: SID, busy: false }, { type: 'done', sessionId: SID }]);
  replyScript = () => [
    { frame: { type: 'sessionBusy', sessionId: SID, busy: true } },
    { frame: { type: 'sessionBusy', sessionId: SID, busy: false }, delay: 300 },
    { frame: { type: 'done', sessionId: SID }, delay: 310 },
  ];
  await sleep(800);
  replyScript = null;
  ok('C5 终态后队列 flush 恰一条', inboundUserFramesOf('queued during phantom window').length === 1,
    `frames=${inboundUserFramesOf('queued during phantom window').length}`);
  ok('C6 flush 派发轮照常收尾（busy 清）', !(await sidBusy()));

  // ══ D 确认网不误伤真 turn（负例）：busy{true} 在窗口内到场 → 网撤 ══════
  await setConfirmMs(800);
  // 慢 turn：busy{true} 250ms 到场（< 800ms 窗口），done 拖到 2.2s
  replyScript = () => [
    { frame: { type: 'sessionBusy', sessionId: SID, busy: true }, delay: 250 },
    { frame: { type: 'sessionBusy', sessionId: SID, busy: false }, delay: 2200 },
    { frame: { type: 'done', sessionId: SID }, delay: 2210 },
  ];
  await sendText('slow but real turn');
  await sleep(1000); // 越过 800ms 窗口（busy{true} 已到场撤网）
  ok('D1 窗口内 busy{true} 到场 ⇒ 网不误清（慢 turn busy 保留）', await sidBusy(), `busy=${await sidBusy()}`);
  await sleep(1500);
  ok('D2 慢 turn 终态照常收 busy', !(await sidBusy()));
  ok('D3 全程零 pageerror', pageErrors.length === 0, JSON.stringify(pageErrors));

  // ══ F 多气泡轮：text/round 边界不是 turn 终态，只有 done 才收 composer ═══
  await setConfirmMs(20000);
  historyFixture = [];
  replyScript = () => [
    { frame: { type: 'sessionBusy', sessionId: SID, busy: true } },
    { frame: { type: 'textDelta', sessionId: SID, delta: '多气泡第一段。' } },
    { frame: { type: 'roundComplete', sessionId: SID }, delay: 80 },
    { frame: { type: 'sessionBusy', sessionId: SID, busy: true }, delay: 90 },
    { frame: { type: 'textDelta', sessionId: SID, delta: '多气泡第二段。' }, delay: 120 },
    { frame: { type: 'sessionBusy', sessionId: SID, busy: false }, delay: 180 },
    { frame: { type: 'done', sessionId: SID, model: 'mock-llm' }, delay: 190 },
  ];
  await sendText('multi bubble turn');
  await sleep(650);
  ok('F1 多气泡轮两段正文都落面', await page.evaluate(() =>
    [...document.querySelectorAll('.bubble.ai')].some(b => (b.textContent || '').includes('多气泡第一段'))
    && [...document.querySelectorAll('.bubble.ai')].some(b => (b.textContent || '').includes('多气泡第二段'))));
  const uiF = await busyUi();
  ok('F2 多气泡轮 done 后 composer 清零', !(await sidBusy()) && uiF.sendShown && !uiF.stopShown, JSON.stringify(uiF));

  // ══ G 黑暗窗重连：done/busy(false) 丢失，历史已 done 但 composer 不得留旧 busy ══
  historyFixture = [
    { type: 'user', text: 'multi bubble turn', timestamp: 1761680000000 },
    { type: 'tool', label: 'Bash', summary: 'isolated smoke', content: 'ok', isError: false, input: {} },
    { type: 'ai', text: '黑暗窗内完成的答复。', durationMs: 100000, model: 'mock-llm', timestamp: 1761680100000, terminal: true },
  ];
  replyScript = () => [
    { frame: { type: 'sessionBusy', sessionId: SID, busy: true } },
    { frame: { type: 'textDelta', sessionId: SID, delta: '重连前的流式片段' }, delay: 30 },
    { frame: { type: 'done', sessionId: SID, model: 'mock-llm' }, delay: 300 },
    { frame: { type: 'sessionBusy', sessionId: SID, busy: false }, delay: 310 },
  ];
  await sendText('terminal lost during reconnect');
  await sleep(120); // busy + 首个流帧已落，终态尚未到
  const prevSeq = conns[conns.length - 1]?.seq || 0;
  serverDown = true;
  await currentWs.close();
  await sleep(450); // 终态对在旧连接关闭后触发 = 黑暗窗内永久丢失
  serverDown = false;
  await page.evaluate(async () => { (await import('/js/ws.js')).forceReconnect(); });
  await waitForNewConnection(prevSeq);
  await sleep(900); // historyPage + getActiveAgents + sessionBusyStates 全链消化
  const terminalHeaders = await page.locator('#chat .turn-header').count();
  ok('G1 重放历史已形成 done 轮（报障同形前提）', terminalHeaders >= 1, `headers=${terminalHeaders}`);
  ok('G2 重连发出主会话权威 busy 查询', sessionBusyQueries().some(m => (m.sessionIds || []).includes(SID)),
    JSON.stringify(sessionBusyQueries().map(m => m.sessionIds)));
  const uiG = await busyUi();
  ok('G3 done 已落历史但 composer 不再幽忙', !(await sidBusy()) && uiG.sendShown && !uiG.stopShown, JSON.stringify(uiG));
  // 场景卫生：旧实现 G3 红时遗留的 busy 在此显式清掉，后续用例互不污染。
  serverSends([{ type: 'sessionBusy', sessionId: SID, busy: false }, { type: 'done', sessionId: SID }]);
  await sleep(250);

  // ══ H agentDone 同构终态：带 sessionId 时必须强制清 busy ═══════════════
  historyFixture = [];
  serverSends([
    { type: 'sessionBusy', sessionId: SID, busy: true },
    { type: 'textDelta', sessionId: SID, delta: 'agentDone 前的输出' },
    { type: 'agentDone', sessionId: SID, agentId: 'root-agent-actor', model: 'mock-llm' },
  ]);
  await sleep(350);
  const uiH = await busyUi();
  ok('H1 agentDone(sessionId) 强制收 busy', !(await sidBusy()) && uiH.sendShown && !uiH.stopShown, JSON.stringify(uiH));
  serverSends([{ type: 'sessionBusy', sessionId: SID, busy: false }, { type: 'done', sessionId: SID }]);
  await sleep(250);

  // ══ J 负例：done 后服务端又权威开启新轮，busy 必须保留（防「一律清死」）══
  serverSends([
    { type: 'sessionBusy', sessionId: SID, busy: true },
    { type: 'done', sessionId: SID },
    { type: 'sessionBusy', sessionId: SID, busy: true },
    { type: 'textDelta', sessionId: SID, delta: '紧接着的新一轮仍在输出' },
  ]);
  await sleep(300);
  const uiJ = await busyUi();
  ok('J1 done 后新轮 busy(true) 仍可置位', (await sidBusy()) && uiJ.stopShown && !uiJ.sendShown, JSON.stringify(uiJ));
  serverSends([{ type: 'sessionBusy', sessionId: SID, busy: false }, { type: 'done', sessionId: SID }]);
  await sleep(250);
  ok('J2 新轮自己的终态照常收 busy', !(await sidBusy()));

  // ══ E 静态钉：接缝在源面在场 ═══════════════════════════════════════
  const mainSrc = await readFile(join(WEB, 'js', 'main.js'), 'utf8');
  const inputSrc = await readFile(join(WEB, 'js', 'input.js'), 'utf8');
  const stateSrc = await readFile(join(WEB, 'js', 'state.js'), 'utf8');
  ok('E1 turn-start 确认网（arm/cancel/fire）在 main.js', /function armTurnConfirm/.test(mainSrc) && /function cancelTurnConfirm/.test(mainSrc));
  ok('E2 派发腿武装确认网（send/drain/offline/inject）', (inputSrc.match(/state\.armTurnConfirm\?\.\(/g) || []).length >= 4,
    `arms=${(inputSrc.match(/state\.armTurnConfirm\?\.\(/g) || []).length}`);
  ok('E3 回执腿带 drain（队列 flush 挂点）', /messageDuplicate[\s\S]{0,4200}scheduleQueueDrain\(sid\)/.test(mainSrc));
  ok('E4 回执守卫读本会话流活动对账（精确腿 + per-session 15s 回落）',
    /sessionStreamActivity\[sid\][^>]*> dispatchAt/.test(mainSrc) && /sinceSidStream >= 15000/.test(mainSrc));
  ok('E5 per-session 流活动戳 + 确认网字段在 state.js', /sessionStreamActivity:\s*\{\}/.test(stateSrc) && /turnConfirmMs:\s*20000/.test(stateSrc));
  ok('E6 depth>0 前缀会话不武装确认网', /startsWith\('delegate-'\)/.test(mainSrc) && /startsWith\('node-'\)/.test(mainSrc));
  ok('E7 重连主会话权威 busy 重同步接缝在场', /getSessionBusy/.test(mainSrc) && /onMessage\('sessionBusyStates'/.test(mainSrc));
  ok('E8 agentDone(sessionId) 走统一终态清理', /onMessage\('agentDone'[\s\S]{0,900}clearBusyFor/.test(mainSrc));

  // ══ 收尾 ════════════════════════════════════════════════════════════
  console.log(failures === 0 ? '\nALL PASS' : `\n${failures} FAILURE(S)`);
} catch (e) {
  failures++;
  console.error('SPEC ERROR:', e);
} finally {
  await browser.close().catch(() => {});
  server.close();
}
process.exit(failures === 0 ? 0 : 1);
