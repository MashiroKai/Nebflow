// queue-one-per-turn.spec.mjs — 修复批「消息排队投递语义回归：多消息排队被一次性全发」
// （2026-10-24 作者报障 15:45 逐字）验收。
//
// 报障逐字：「我们的投递消息的排队功能回退了以后，又会出现排队的消息一次性发送的
// 问题。正确的行为是，比如我的队列中有消息在排队，应该是你轮次结束，投递一个消息，
// 再次结束，再投递一个消息。而不是一股脑排队了几个消息，一次全部发送」
//
// 语义钉（作者拍板）：队列积压 N 条 ⇒ 回合结束 → 投递 1 条 → 会话消费该条产生新回合
// → 回合再结束 → 再投下 1 条。**投递前提 = 上一个回合真正结束（事实闸），不是计时器
// 节流。**
//
// 根因（客户端 drain 闸，引擎侧零触碰；服务端逐条语义经生产日志核对完好——
// 2026-10-24 全日 `pending-events-injected-at-tools-complete` 恒 events=1）：
// main.js 派发闸旧形只靠「busy 标志 + 运行中事实」两条件挡重复——凡某条 speculative
// 腿把 busy 提前清掉（陈旧 sessionBusy{false} / 判重回执回落腿 / 确认网误判），闸即
// 失守，下一条排队件被投进**仍在跑的 turn**。生产病历 2026-10-24 15:41（Nebula 会话
// d7c3cb54 族）：m1（15:37 入队）15:41:22 投出开轮，m2（15:39 入队）15:41:48 于 m1
// turn 运行中被投出 → 服务端排队后在同一 turn 内被消费（turn-complete msgs 52→56
// 一并作答）=「一次性发送」。
//
// 修法（本批，客户端三件）：① 派发闸新增**投递未决闸（③）**——上次派发开启的 turn
// 已被引擎确认开轮（新轮事实晚于派发）但终态戳未越过派发时刻 ⇒ 纵然 busy 已被
// speculative 腿清掉也不投，等真实终态（事实闸，非计时器）；② turn-start 确认网加
// 引擎开轮事实否决（静默慢轮不是幻影）；③ 各权威收口点（sessionBusy{false} /
// compactComplete / 判重精确腿 / stop 键 / 看门真死 / 后台对账器）落终态戳——
// queue-idle-flush 的四条「零事实形态照常投递」语义逐字保留。
//
// 场景（真模块图 + routeWebSocket 桩服务端；harness 同 queue-idle-flush.spec.mjs 族）：
//   P  作者钉三件套：锚轮占 busy → 排队 3 条 → 锚终态恰投 m1 → m1 轮运行中
//     （2.6s 慢轮 + 冷却窗越界）m2/m3 零抢跑 → m1 终态恰投 m2 → m2 轮内 m3 零抢跑
//     → m2 终态恰投 m3 → 队列清空；全局每条恰一次、按队序、任一时刻投递窗口内
//     不出现 ≥2 条（全程无 flush≥2）。
//   X1 回归捕获器（2026-10-24 15:41 病历形态·speculative busy 清除类）：
//     m1 派发开轮（busy{true} 在场）→ 轮中段桩发**陈旧 sessionBusy{false}**（无
//     done——幻影帧/对账残帧形态）⇒ busy 被清 ⇒ 旧闸在此投出 m2（运行中 turn 被
//     插队，旧实现必红）；新闸投递未决闸按住，真实终态到场才投。
//   X2 回归捕获器（判重回执**回落腿**误收真轮 busy）：m1 派发开轮 + 会话流活动戳
//     置旧（15s 静默形态）→ 桩发 id 对不上的陈旧 messageDuplicate ⇒ 回落腿清 busy
//     ⇒ 旧闸在 D+2s 冷却到界投出 m2（旧实现必红）；新闸（回落腿不落终态戳）按住，
//     真实终态到场才投。
//   E  静态钉：投递未决闸 / 确认网开轮事实否决 / 六处权威终态戳在源面在场。
//
// Run: node tests/queue-one-per-turn.spec.mjs
// 自包含：静态服务器随机隔离端口 + routeWebSocket 桩，finally 必关。
import { chromium } from 'playwright-core';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const SID = 's-qopt-e2e';

let failures = 0;
function ok(name, cond, extra = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
}
const sleep = ms => new Promise(r => setTimeout(r, ms));
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2' };

// ── 桩服务端状态 ────────────────────────────────────────────────────
let currentWs = null;
const conns = [];
let replyScript = null; // frame => [{frame, delay}]（一次性：出线用户内容帧只消费一枚）

function isUserContentFrame(m) {
  return !m.type && typeof m.content === 'string';
}

function wireUp(ws) {
  currentWs = ws;
  const log = { inbound: [] };
  conns.push(log);
  ws.onMessage(raw => {
    let m; try { m = JSON.parse(raw); } catch { return; }
    log.inbound.push(m);
    if (m.type === 'ping') { ws.send(JSON.stringify({ type: 'pong' })); return; }
    if (m.type === 'getHistory') {
      ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0, total: 0 }));
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
  ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: '逐条投递E2E' }], activeId: SID, folders: [] }));
}

/** 服务端注入任意帧（模拟引擎事件流）。 */
const serverSends = (frames) => {
  for (const f of frames) currentWs.send(JSON.stringify(f));
};

const outboundUserFramesOf = (content) => conns.flatMap(c => c.inbound).filter(m => isUserContentFrame(m) && m.content === content);
const firstOutboundIndexOf = (content) => conns.flatMap(c => c.inbound).findIndex(m => isUserContentFrame(m) && m.content === content);

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
  // drain 派发轮的**快**收尾应答（busy{true} 即刻 + 终态对 250ms 后）。
  const drainTurnScript = () => [
    { frame: { type: 'sessionBusy', sessionId: SID, busy: true } },
    { frame: { type: 'sessionBusy', sessionId: SID, busy: false }, delay: 250 },
    { frame: { type: 'done', sessionId: SID }, delay: 260 },
  ];
  // drain 派发轮的**慢**收尾应答（busy 挂 2.6s——越过 2s 派发冷却窗，给「运行中
  // 不抢跑」断言留观测窗；终态对最后到场）。
  const slowTurnScript = () => [
    { frame: { type: 'sessionBusy', sessionId: SID, busy: true } },
    { frame: { type: 'sessionBusy', sessionId: SID, busy: false }, delay: 2600 },
    { frame: { type: 'done', sessionId: SID }, delay: 2610 },
  ];
  const holdBusyScript = () => [{ frame: { type: 'sessionBusy', sessionId: SID, busy: true } }];
  // 条件等待（100ms 步进；超时返回 false）——派发冷却窗（2s）跨场景残留使固定
  // sleep 不可靠，凡「等到发生」类断言一律轮询，凡「窗内不得发生」类断言前置 settle。
  const waitUntil = async (cond, timeoutMs) => {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      if (await cond()) return true;
      await sleep(100);
    }
    return cond();
  };
  // 场景间静置：越过后一次派发的 2s 冷却窗 + 清掉幻影草签（busy{false}+done 终态对），
  // 保证每场景从「空闲 + 队列空 + 冷却已过」的确定形态起步。
  const settle = async () => {
    await sleep(2300);
    serverSends([{ type: 'sessionBusy', sessionId: SID, busy: false }, { type: 'done', sessionId: SID }]);
    await sleep(300);
  };

  ok('boot 后在线', conns.length > 0);
  await setConfirmMs(600); // 幻影草签快速收口（缩短场景间隔）

  // ══ P 作者钉三件套：一次终态恰投一条、全程无 flush≥2 ══════════════
  {
    replyScript = holdBusyScript;
    await sendText('p anchor');
    await sleep(300);
    ok('P.1 锚轮直发 + busy 挂住', outboundUserFramesOf('p anchor').length === 1 && (await sidBusy()));
    await sendText('p m1'); await sleep(200);
    await sendText('p m2'); await sleep(200);
    await sendText('p m3'); await sleep(200);
    ok('P.2 三条全部入队不出线',
      outboundUserFramesOf('p m1').length === 0 && outboundUserFramesOf('p m2').length === 0 && outboundUserFramesOf('p m3').length === 0
      && (await queuedItems()).length === 3);
    // 锚轮终态 ⇒ 恰投 m1 一条（m2/m3 必须原地不动）
    replyScript = slowTurnScript; // m1 的派发轮 = 慢轮（越冷却窗）
    serverSends([{ type: 'sessionBusy', sessionId: SID, busy: false }, { type: 'done', sessionId: SID }]);
    ok('P.3 锚终态后恰投 m1 一条', await waitUntil(() => outboundUserFramesOf('p m1').length === 1, 4000),
      `m1=${outboundUserFramesOf('p m1').length}`);
    ok('P.4 投 m1 的同一窗口零 flush≥2（m2/m3 原地不动）',
      outboundUserFramesOf('p m2').length === 0 && outboundUserFramesOf('p m3').length === 0 && (await queuedItems()).length === 2);
    await sleep(800); // m1 轮运行中（busy{true} 在场；终态 2.6s 后才到）
    ok('P.5 m1 轮运行中 m2/m3 不被抢跑（2.6s 慢轮 + 冷却窗越界）',
      outboundUserFramesOf('p m2').length === 0 && outboundUserFramesOf('p m3').length === 0,
      `m2=${outboundUserFramesOf('p m2').length} m3=${outboundUserFramesOf('p m3').length}`);
    // m1 终态 ⇒ 恰投 m2 一条（m2 轮同样慢轮）
    replyScript = slowTurnScript;
    ok('P.6 m1 终态后恰投 m2 一条', await waitUntil(() => outboundUserFramesOf('p m2').length === 1, 6000),
      `m2=${outboundUserFramesOf('p m2').length}`);
    await sleep(800); // m2 轮运行中
    ok('P.7 m2 轮运行中 m3 不被抢跑', outboundUserFramesOf('p m3').length === 0,
      `m3=${outboundUserFramesOf('p m3').length}`);
    // m2 终态 ⇒ 恰投 m3 一条
    replyScript = drainTurnScript;
    ok('P.8 m2 终态后恰投 m3 一条', await waitUntil(() => outboundUserFramesOf('p m3').length === 1, 6000),
      `m3=${outboundUserFramesOf('p m3').length}`);
    replyScript = null;
    await sleep(600);
    ok('P.9 队列全清', (await queuedItems()).length === 0);
    ok('P.10 投递按队序（m1 < m2 < m3）',
      firstOutboundIndexOf('p m1') < firstOutboundIndexOf('p m2') && firstOutboundIndexOf('p m2') < firstOutboundIndexOf('p m3'));
    ok('P.11 每条恰一次零重复（全程无一次 flush≥2）',
      outboundUserFramesOf('p anchor').length === 1 && outboundUserFramesOf('p m1').length === 1
      && outboundUserFramesOf('p m2').length === 1 && outboundUserFramesOf('p m3').length === 1);
  }

  // ══ X1 回归捕获器：轮中段陈旧 busy{false}（speculative busy 清除类）══════
  // 病历形态：m1 派发开轮（引擎 busy{true} 已确认）⇒ 轮中段一帧无 done 的陈旧
  // sessionBusy{false} 把 busy 清掉 ⇒ 旧闸（唯 busy + 运行中事实两条件）失守，
  // 冷却到界（D+2s）把 m2 投进仍在跑的 m1 轮 = 一次性发送；新闸投递未决闸按住
  // （开轮事实在场 + 终态戳未越派发时刻），真实终态到场才放行。
  {
    await settle();
    replyScript = holdBusyScript;
    await sendText('x1 anchor');
    await sleep(300);
    await sendText('x1 m1'); await sleep(200);
    await sendText('x1 m2'); await sleep(200);
    ok('X1.1 锚轮挂住 + 两条入队', (await sidBusy()) && (await queuedItems()).length === 2 && outboundUserFramesOf('x1 m1').length === 0);
    serverSends([{ type: 'sessionBusy', sessionId: SID, busy: false }, { type: 'done', sessionId: SID }]);
    // m1 派发轮：busy{true} 即刻、终态 3.4s 后（留足 D+2s 冷却越界观测窗）。
    replyScript = () => [
      { frame: { type: 'sessionBusy', sessionId: SID, busy: true } },
      // 轮中段（+300ms）：陈旧/幻影 busy{false}（无 done = 非终态，speculative 清除）
      { frame: { type: 'sessionBusy', sessionId: SID, busy: false }, delay: 300 },
      // 真实终态对（+3400ms）：m1 turn 到此才真正结束
      { frame: { type: 'sessionBusy', sessionId: SID, busy: false }, delay: 3100 },
      { frame: { type: 'done', sessionId: SID }, delay: 3110 },
    ];
    ok('X1.2 m1 派发开轮', await waitUntil(() => outboundUserFramesOf('x1 m1').length === 1, 4000),
      `m1=${outboundUserFramesOf('x1 m1').length}`);
    await sleep(600); // 越过幻影 busy{false}（+300ms）之后、冷却到界（D+2s）之前
    ok('X1.3 幻影 busy{false} 清 busy（前置形态确认）', !(await sidBusy()));
    await sleep(2200); // 跨过 D+2s 冷却到界（旧实现在此投出 m2 = RED；真实终态 D+3.4s 未到）
    ok('X1.4 冷却到界 m2 不被投进运行中轮（旧实现必红）', outboundUserFramesOf('x1 m2').length === 0,
      `m2=${outboundUserFramesOf('x1 m2').length}`);
    replyScript = drainTurnScript; // m2 的派发轮应答
    ok('X1.5 m1 真实终态后 m2 照常投递（恰一条）', await waitUntil(() => outboundUserFramesOf('x1 m2').length === 1, 6000),
      `m2=${outboundUserFramesOf('x1 m2').length}`);
    replyScript = null;
    await sleep(600);
    ok('X1.6 队列清空 + busy 清', (await queuedItems()).length === 0 && !(await sidBusy()));
  }

  // ══ X2 回归捕获器：判重回执回落腿误收真轮 busy ═════════════════════
  // m1 派发开轮（真轮在跑）+ 会话流活动戳置旧（15s 静默形态）→ 桩发 id 对不上的
  // 陈旧 messageDuplicate ⇒ 回落腿（per-session 15s 静默窗）清 busy 并触发 drain。
  // 回落腿**不落终态戳**（它可能撞上真实静默轮）⇒ 新闸投递未决闸在 D+2s 到界时
  // 按住 m2（旧实现必红：无未决闸，直接投出）；真实终态到场才放行。
  // （确认网在本场景	defer 到 60s：生产网预算 20s > 回落腿 15s 静默窗，回落腿恒
  // 先于确认网到场；测试加速网（600ms）会抢跑并以其真幻影戳放行投递 = 另一形态，
  // 非本场景捕获对象。）
  {
    await setConfirmMs(60000); // 确认网 defer（场景后恢复）
    await settle();
    replyScript = holdBusyScript;
    await sendText('x2 anchor');
    await sleep(300);
    await sendText('x2 m1'); await sleep(200);
    await sendText('x2 m2'); await sleep(200);
    ok('X2.1 锚轮挂住 + 两条入队', (await sidBusy()) && (await queuedItems()).length === 2 && outboundUserFramesOf('x2 m1').length === 0);
    serverSends([{ type: 'sessionBusy', sessionId: SID, busy: false }, { type: 'done', sessionId: SID }]);
    replyScript = (m) => [
      { frame: { type: 'sessionBusy', sessionId: SID, busy: true } },
      // 轮中段（+400ms）：陈旧判重回执（id ≠ 最新派发 = 旧消息的回执）
      { frame: { type: 'messageDuplicate', sessionId: SID, clientMessageId: 'stale-old-id' }, delay: 400 },
      // 真实终态对（+3400ms）
      { frame: { type: 'sessionBusy', sessionId: SID, busy: false }, delay: 3000 },
      { frame: { type: 'done', sessionId: SID }, delay: 3010 },
    ];
    ok('X2.2 m1 派发开轮', await waitUntil(() => outboundUserFramesOf('x2 m1').length === 1, 4000),
      `m1=${outboundUserFramesOf('x2 m1').length}`);
    // 把会话流活动戳置旧（模拟 15s 静默真轮）⇒ 回落腿判定成立
    await page.evaluate(async (sid) => {
      const s = await import('/js/state.js');
      s.default.sessionStreamActivity[sid] = Date.now() - 20000;
    }, SID);
    await sleep(900); // 回执（+400ms）已处理：回落腿清 busy + 触发 drain（被冷却窗压到 D+2s）
    ok('X2.3 回落腿清 busy（前置形态确认）', !(await sidBusy()));
    await sleep(1400); // 站到 D+2.3s~2.5s 观测窗：冷却已到界（D+2s）、真实终态（D+3s）未到
    ok('X2.4 冷却到界 m2 不被投进运行中轮（旧实现必红）', outboundUserFramesOf('x2 m2').length === 0,
      `m2=${outboundUserFramesOf('x2 m2').length}`);
    replyScript = drainTurnScript; // m2 的派发轮应答
    ok('X2.5 m1 真实终态后 m2 照常投递（恰一条）', await waitUntil(() => outboundUserFramesOf('x2 m2').length === 1, 6000),
      `m2=${outboundUserFramesOf('x2 m2').length}`);
    replyScript = null;
    await setConfirmMs(600); // 确认网预算恢复
    await sleep(600);
    ok('X2.6 队列清空 + busy 清', (await queuedItems()).length === 0 && !(await sidBusy()));
  }

  // ══ E 静态钉：投递未决闸 / 确认网否决 / 六处权威终态戳在源面在场 ════
  {
    const mainSrc = await readFile(join(WEB, 'js', 'main.js'), 'utf8');
    const inputSrc = await readFile(join(WEB, 'js', 'input.js'), 'utf8');
    const chatSrc = await readFile(join(WEB, 'js', 'chat.js'), 'utf8');
    ok('E1 投递未决闸（开轮事实 + 终态戳双判）在派发定时器体',
      /unresolvedFactAt > dispatchedAt && termStampedAt <= dispatchedAt/.test(mainSrc));
    ok('E2 终态戳事实源 = state.lastTerminalAt（闸体读数）', /state\.lastTerminalAt\[sid\] \|\| 0/.test(mainSrc));
    ok('E3 sessionBusy{busy:false} 条件落终态戳（双丢形态落、在跑确认轮不落）',
      /AUTHORITATIVE end-of-turn signal/.test(mainSrc)
      && /\(queueTurnFactAt\.get\(sid\) \|\| 0\) <= \(lastQueueDispatchAt\.get\(sid\) \|\| 0\)/.test(mainSrc));
    ok('E4 确认网开轮事实否决（静默慢轮不是幻影）',
      /factAt > \(state\.turnStartTimes\[sid\] \|\| 0\)/.test(mainSrc));
    ok('E5 确认网真幻影分支落权威无-turn 戳',
      /state\.lastTerminalAt\[sid\] = Date\.now\(\);\s*\n\s*scheduleQueueDrain\(sid\);\s*\n\s*\}, budget\);/.test(mainSrc));
    ok('E6 compactComplete 落终态戳', /compactComplete[\s\S]{0,1200}/.test(mainSrc)
      && (mainSrc.match(/state\.lastTerminalAt\[sid\] = Date\.now\(\);/g) || []).length >= 5,
      `stamps=${(mainSrc.match(/state\.lastTerminalAt\[sid\] = Date\.now\(\);/g) || []).length}`);
    ok('E7 判重精确腿落戳、回落腿不落（回落腿可能撞真轮）',
      /if \(idMatchesLatestSend\) state\.lastTerminalAt\[sid\] = Date\.now\(\);/.test(mainSrc));
    ok('E8 stop 键落终态戳（input.js R3 保留）', /state\.lastTerminalAt\[sid\] = Date\.now\(\);[\s\S]{0,80}state\.scheduleQueueDrain\?\.\(sid\)/.test(inputSrc));
    ok('E9 看门真死收口落终态戳（chat.js）', /state\.lastTerminalAt\[sid\] = Date\.now\(\);[\s\S]{0,80}state\.scheduleQueueDrain\?\.\(sid\)/.test(chatSrc));
    ok('E10 全程零 pageerror', pageErrors.length === 0, JSON.stringify(pageErrors));
  }

  // ══ 收尾 ══════════════════════════════════════════════════════════
  console.log(failures === 0 ? '\nALL PASS' : `\n${failures} FAILURE(S)`);
} catch (e) {
  failures++;
  console.error('SPEC ERROR:', e);
} finally {
  await browser.close().catch(() => {});
  server.close();
}
process.exit(failures === 0 ? 0 : 1);
