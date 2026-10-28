// f1-send-queue.spec.mjs — 修复批 F1「WS 未开时按 Enter 发纯文本 = 静默丢」（审计 S4，
// 头号丢面，input.js:237-240 早退腿）验收。
//
// 被测面 = **真发送链**（真实 web 树 + 真模块图 + 真用户动作，WS 层 = routeWebSocket
// 桩服务端；桩支持 serverDown 模式 = 新连接立即掐断，模拟断线窗）：
//
//   ① 断线入队 + 重连自动补发：WS 断开 → Enter 发消息 ⇒ 气泡上屏带「待送达」时钟
//     小标（.msg-pending-mark）+ 入队持久化（localStorage 队列项 offline:true）+ 零出线；
//     重连 ⇒ 自动补发、帧带入队时钉死的同一 clientMessageId（#569 幂等轨）⇒ 恰一帧
//     出线、零重复气泡、小标摘除（翻已送达态）、队列排空。
//   ② 刷新持久化：断线入队后整页刷新 ⇒ 重连（首连）仍自动补发（persistQueue 轨生效），
//     同 id 恰一帧。
//   ③ 在线零回归：正常在线发送 ⇒ 即时出线、气泡**无**待送达小标、队列恒空。
//   ④ wire/源面静态钉：早退腿已改入队（旧 console.warn+return 形态不在）、offline 标记
//     随 persistQueue 落盘、onReconnect 注册 flush、cap100 在场。
//
// 红线自检：断言面 = 线上帧 + 气泡行数 + 小标 DOM + localStorage 队列，不触碰样式值。
//
// Run: node tests/f1-send-queue.spec.mjs
// 自包含：静态服务器随机隔离端口 + routeWebSocket 桩，finally 必关。
import { chromium } from 'playwright-core';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const SID = 's-f1-e2e';

let failures = 0;
function ok(name, cond, extra = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
}
const sleep = ms => new Promise(r => setTimeout(r, ms));
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2' };

// ── 桩服务端状态 ────────────────────────────────────────────────────
let currentWs = null;
let serverDown = false;        // true = 新连接立即掐断（断线窗）
let holdTurn = false;          // true = 受理帧只回 sessionBusy{true}，不发终态（模拟引擎持轮）
let connSeq = 0;
const conns = [];              // 每条连接的入站帧日志
const accepted = [];           // 受理的用户内容帧

function isUserContentFrame(m) {
  return !m.type && typeof m.content === 'string';
}

function wireUp(ws) {
  if (serverDown) { // 断线窗：握手即掐（客户端走 onclose → 指数退避重连）
    try { ws.close(); } catch { /* noop */ }
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
      ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0, total: 0 }));
      return;
    }
    if (isUserContentFrame(m)) {
      accepted.push(m);
      ws.send(JSON.stringify({ type: 'sessionBusy', sessionId: m.sessionId, busy: true }));
      if (holdTurn) return; // ⑤ busy 轨用例：持轮不发终态（引擎长轮形态）
      setTimeout(() => {
        try {
          ws.send(JSON.stringify({ type: 'sessionBusy', sessionId: m.sessionId, busy: false }));
          ws.send(JSON.stringify({ type: 'done', sessionId: m.sessionId, model: 'mock-llm' }));
        } catch { /* 连接已被测试关闭 */ }
      }, 120);
    }
  });
  // 引导帧（保持应用安静 + 挂上被测会话）
  ws.send(JSON.stringify({ type: 'configData', config: '{"features":{},"console":{"inputMode":"queue"}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
  ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'F1发送队列E2E' }], activeId: SID, folders: [] }));
}

async function waitForNewConnection(prevSeq, timeoutMs = 15000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (conns.length > 0 && conns[conns.length - 1].seq > prevSeq) return conns[conns.length - 1];
    await sleep(100);
  }
  throw new Error('client did not reconnect in time');
}

const inboundUserFramesOf = (content) => conns.flatMap(c => c.inbound).filter(m => isUserContentFrame(m) && m.content === content);

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
    // 刻意**不**清 message_queue 键（addInitScript 每次导航都跑，包括场景②被测的
    // page.reload()——清了就把持久化轨在 boot 恢复前抹掉）；新 context 本就为空。
  });
  const page = await ctx.newPage();
  const pageErrors = [];
  page.on('pageerror', e => pageErrors.push(e.message));
  await page.route('**/api/**', r => r.fulfill({ json: {} }));
  await page.routeWebSocket(/\/ws/, ws => wireUp(ws));

  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#input', { state: 'visible', timeout: 20000 });
  await sleep(1500); // 引导帧消化（sessionList → 视图挂载）

  const clientConnected = () => page.evaluate(async () => {
    const s = await import('/js/state.js');
    return s.default.connected === true;
  });
  const bubbleCount = (txt) => page.evaluate((t) => {
    return [...document.querySelectorAll('.row.user .bubble.user')]
      .filter(b => (b.textContent || '').includes(t)).length;
  }, txt);
  const pendingMarkCount = (txt) => page.evaluate((t) => {
    return [...document.querySelectorAll('.row.user')]
      .filter(r => (r.textContent || '').includes(t))
      .map(r => r.querySelectorAll('.msg-pending-mark').length)
      .reduce((a, b) => a + b, 0);
  }, txt);
  // 存储键 = branding 命名空间（key() = `${brand.lowerName}_message_queue`；本仓静态
  // 服/测试态 fallback brand = 'stand'，非硬编码 'nebflow'）——按既有 msgqueue 系 spec
  // 同款「包含匹配」读取（msgqueue-image-mime.spec.mjs:146 同律），不钉死前缀。
  const queuedItems = () => page.evaluate(() => {
    try {
      const raw = localStorage.getItem(Object.keys(localStorage).find((k) => k.includes('message_queue')));
      return Object.values(JSON.parse(raw || '{}')).flat();
    } catch { return []; }
  });
  /** 进入断线窗：先落 serverDown（新连接即掐）再掐当前连接，等客户端观测到断开。 */
  const enterOutage = async () => {
    serverDown = true;
    const seq = conns.length > 0 ? conns[conns.length - 1].seq : 0;
    try { await currentWs.close(); } catch { /* 已断 */ }
    const deadline = Date.now() + 8000;
    while (Date.now() < deadline) {
      if (!(await clientConnected())) return seq;
      await sleep(100);
    }
    throw new Error('client did not observe disconnect in time');
  };
  /** 出断线窗：放行 + forceReconnect 跳退避，等新连接。 */
  const exitOutage = async (prevSeq) => {
    serverDown = false;
    await page.evaluate(async () => { (await import('/js/ws.js')).forceReconnect(); });
    return waitForNewConnection(prevSeq);
  };

  // ══ ① 断线入队 → 重连自动补发（同 id，无双发，翻面）══════════════════
  ok('boot 后在线', await clientConnected());
  const seq1 = await enterOutage();

  await page.fill('#input', 'offline hello one');
  await page.keyboard.press('Enter');
  await sleep(600);

  ok('1a 断线发送零出线（未写 socket）', inboundUserFramesOf('offline hello one').length === 0,
    `inbound=${inboundUserFramesOf('offline hello one').length}`);
  ok('1b 气泡上屏（乐观呈现）', (await bubbleCount('offline hello one')) === 1,
    `count=${await bubbleCount('offline hello one')}`);
  ok('1c 气泡带「待送达」时钟小标', (await pendingMarkCount('offline hello one')) === 1,
    `marks=${await pendingMarkCount('offline hello one')}`);
  const q1 = await queuedItems();
  ok('1d 入队持久化（localStorage 队列项 offline:true + pinned id）',
    q1.length === 1 && q1[0].offline === true && typeof q1[0].clientMessageId === 'string' && q1[0].clientMessageId.length > 0,
    JSON.stringify(q1.map(i => ({ offline: i.offline, id: i.clientMessageId }))));
  const pinnedId1 = q1[0]?.clientMessageId;
  ok('1e 输入框已清空（发送语义闭环，不是草稿残留）', await page.evaluate(() => document.getElementById('input').value === ''));

  const conn2 = await exitOutage(seq1);
  await sleep(900); // onopen → flushOfflineQueue → 帧出线 + 桩 turn 回放消化
  const f1 = inboundUserFramesOf('offline hello one');
  ok('1f 重连后自动补发恰一帧', f1.length === 1, `frames=${f1.length}`);
  ok('1g 补发帧 = 入队钉死的同一 clientMessageId（#569 幂等轨）',
    f1.length === 1 && f1[0].clientMessageId === pinnedId1,
    `frame=${f1[0]?.clientMessageId}, pinned=${pinnedId1}`);
  ok('1h 零重复气泡（补发不渲染第二份）', (await bubbleCount('offline hello one')) === 1,
    `count=${await bubbleCount('offline hello one')}`);
  ok('1i 待送达小标已摘除（翻已送达态）', (await pendingMarkCount('offline hello one')) === 0,
    `marks=${await pendingMarkCount('offline hello one')}`);
  ok('1j 队列排空', (await queuedItems()).length === 0, JSON.stringify(await queuedItems()));

  // ══ ② 断线入队 → 整页刷新 → 重连仍补发（持久化生效）══════════════════
  const seq2 = await enterOutage();
  await page.fill('#input', 'offline survives reload');
  await page.keyboard.press('Enter');
  await sleep(600);
  const q2 = await queuedItems();
  ok('2a 断线入队（刷新前）', q2.length === 1 && q2[0].offline === true,
    JSON.stringify(q2.map(i => ({ offline: i.offline }))));
  const pinnedId2 = q2[0]?.clientMessageId;

  serverDown = false; // 刷新后首连即通
  await page.reload({ waitUntil: 'load' });
  await page.waitForSelector('#input', { state: 'visible', timeout: 20000 });
  await sleep(1800); // boot + onopen flush + 帧出线
  const f2 = inboundUserFramesOf('offline survives reload');
  ok('2b 刷新后重连仍自动补发恰一帧（persistQueue 轨）', f2.length === 1,
    `frames=${f2.length}（connSeq 现值 ${conns[conns.length - 1]?.seq}，刷新前 ${seq2}）`);
  ok('2c 补发帧 id = 刷新前钉死的 id（跨刷新幂等键稳定）',
    f2.length === 1 && f2[0].clientMessageId === pinnedId2,
    `frame=${f2[0]?.clientMessageId}, pinned=${pinnedId2}`);
  ok('2d 队列排空（刷新后）', (await queuedItems()).length === 0);

  // ══ ③ 在线零回归：正常发送即时出线、无小标、队列恒空 ══════════════════
  await page.fill('#input', 'online normal send');
  await page.keyboard.press('Enter');
  await sleep(900);
  const f3 = inboundUserFramesOf('online normal send');
  ok('3a 在线发送即时出线恰一帧', f3.length === 1 && !!f3[0].clientMessageId,
    JSON.stringify(f3.map(f => f.clientMessageId)));
  ok('3b 在线气泡**无**待送达小标', (await bubbleCount('online normal send')) === 1
    && (await pendingMarkCount('online normal send')) === 0,
    `bubbles=${await bubbleCount('online normal send')}, marks=${await pendingMarkCount('online normal send')}`);
  ok('3c 在线发送不入队', (await queuedItems()).length === 0);
  ok('3d 全程零 pageerror', pageErrors.length === 0, JSON.stringify(pageErrors));

  // ══ ⑤ busy 排队项断连补发轨（audit ②26/⑥38 残余，2026-10-25 P1 批）════
  // busy 排队项（WS 开、轮次运行中入队，不带 offline 标记，两轨隔离设计）的
  // 唯一投递挂点 = 终态帧族 ⇒ 断连期终态被吞 ⇒ 客户端 busy 镜像/开轮事实陈旧
  // （引擎 onopen 引导族不重放 per-session busy 态）。收口 = input.js
  // rearmBusyQueueDrains（重连腿）：补触发 drain（投递未决闸挡即刺抢跑）+
  // turn-start 确认网换新窗口基点重挂（引擎静默空闲 20s ⇒ 幻影收口：权威
  // 无-turn 戳 + drain 恰投一帧）。桩侧 = holdTurn 持轮、终态永不出线。
  holdTurn = true;
  await page.fill('#input', 'busy held q1');
  await page.keyboard.press('Enter');
  await sleep(500);
  ok('5a 持轮首条出线（桩只回 busy{true}，终态扣发）',
    inboundUserFramesOf('busy held q1').length === 1,
    `frames=${inboundUserFramesOf('busy held q1').length}`);
  await page.fill('#input', 'busy queued q2');
  await page.keyboard.press('Enter');
  await sleep(400);
  const q5 = await queuedItems();
  ok('5b busy 中第二条入 busy 轨（无 offline 标记 = 两轨隔离语义）',
    q5.length === 1 && !q5[0].offline,
    JSON.stringify(q5.map(i => ({ offline: !!i.offline, id: i.clientMessageId }))));
  holdTurn = false;
  const seq5 = await enterOutage();
  // 引擎在断连窗内真终态（终态帧被吞 —— 客户端侧永不可见）。
  await exitOutage(seq5);
  await sleep(1200); // 重连腿先跑：陈旧开轮事实在场 ⇒ 投递未决闸必须挡住抢跑
  ok('5c 重连即刺不抢跑（queue-one-per-turn 语义不因断连放松）',
    inboundUserFramesOf('busy queued q2').length === 0,
    `frames=${inboundUserFramesOf('busy queued q2').length}`);
  const deadline5 = Date.now() + 26000; // 确认网 20s 预算 + drain 50ms + 余量
  while (Date.now() < deadline5 && inboundUserFramesOf('busy queued q2').length === 0) await sleep(300);
  const f5 = inboundUserFramesOf('busy queued q2');
  ok('5d 确认网幻影收口后 busy 轨补发恰一帧（断连补发轨本体）', f5.length === 1,
    `frames=${f5.length}`);
  ok('5e busy 轨补发后排空', (await queuedItems()).length === 0);
  const src5 = await readFile(join(WEB, 'js', 'input.js'), 'utf8');
  ok('5f 重连腿源钉：rearmBusyQueueDrains 挂 onReconnect（补 drain + 换窗确认网）',
    /function rearmBusyQueueDrains/.test(src5)
      && /rearmBusyQueueDrains\(\)/.test(src5)
      && /state\.scheduleQueueDrain\?\.\(sid\)/.test(src5)
      && /state\.armTurnConfirm\?\.\(sid\)/.test(src5)
      && /!it\.offline/.test(src5));

  // ══ ④ 源面静态钉（与实现同树同读数）══════════════════════════════════
  const src = await readFile(join(WEB, 'js', 'input.js'), 'utf8');
  ok('4a 旧静默早退形态已移除', !/console\.warn\('\[send\] ws not open:'/.test(src));
  ok('4b 早退腿改走离线入队', /enqueueOfflineMessage\(v, text\)/.test(src));
  ok('4c offline 标记随 persistQueue 落盘', /offline:\s*it\.offline === true/.test(src));
  ok('4d 重连注册自动补发', /onReconnect\(\(\) => \{?\s*\n?\s*try \{ flushOfflineQueue\(\)/.test(src));
  ok('4e 队列 cap100 + 溢出告警', /QUEUE_CAP = 100/.test(src) && /queue overflow/.test(src));
  const chatSrc = await readFile(join(WEB, 'js', 'chat.js'), 'utf8');
  ok('4f renderUserBubble 支持 pending 小标', /opts && opts\.pending/.test(chatSrc) && /msg-pending-mark/.test(chatSrc));

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
