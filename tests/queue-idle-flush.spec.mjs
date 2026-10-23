// queue-idle-flush.spec.mjs — 修复批「排队消息空闲不自动投递」（2026-10-23）验收。
//
// 报障（作者 2026-10-23 15:10 逐字）：「我发的消息进入排队队列之后，有时候，你完成
// 回复进入空闲状态时，我的消息也不会自动投递。」——#768 回退批恢复的 10-21 BASE
// 排队形态（busy 入队 → 回复完成空闲 → drain 自动投递）在部分轮次形态下不触发。
//
// 根因（客户端 drain 闸，引擎侧零触碰）：main.js 的 per-turn 窗口令牌（旧
// drainWindowUsed：派发即关窗，「新一轮事实」busy{true}/流帧到场才开窗）在派发
// 开启的 turn **零新轮事实**时永久关死——queued /compact 的压缩轮、零流帧轮、
// 幻影派发（判重回执/确认网收口）、断线丢 busy{true} 帧都是无事实形态；此后每个
// 终止帧照样触发 drain 定时器却全数卡死在窗口闸上。另有两条真实 busy→idle 迁移
// （stop 键、看门真死收口）只清 busy 不触发 drain = 队列滞留。
//
// 修法（本批）：闸形改「派发冷却 + 运行中事实」双条件——① 派发后 C=2s 冷却窗压
// 同 turn 终态对第二帧（re-arm 到界不丢触发）；② 冷却后仅当「上次派发后有新轮事实
// 且 busy 未清」（被派件 turn 仍在跑）才挡，零事实形态空闲即投递。stop 键 / 看门
// 真死 / 后台代理对账器三处补 drain 触发腿。引擎零触碰。
//
// 场景（真模块图 + routeWebSocket 桩服务端，真用户动作驱动；harness 同
// busy-lifecycle.spec.mjs 族）：
//   P1 回复完成→空闲→队列自动投递（主钉）。
//   P2 回复结束瞬间入队竞态双序：(a) 入队先于终态帧处理 ⇒ 照常投递；
//      (b) 终态已处理后再发送 ⇒ 直发不出队（无滞留队列项）。
//   P3 多条排队依次投递：一次终态恰投一条、按队序、运行中 turn 不被抢跑。
//   R1 零新轮事实轮（压缩轮形态：compactStart/compactComplete 包裹、无 busy{true}
//      无流帧）——旧窗口闸在此永久滞留（回归捕获器，旧实现必红）。
//   R2 幻影派发（判重回执收口、无 turn）后余件照常投递（旧窗口闸必红）。
//   R3 stop 键清 busy 即投递（interrupted 帧缺席形态，旧实现必红）。
//   E  静态钉：新闸形接缝 / 三条补腿在源面在场。
//
// Run: node tests/queue-idle-flush.spec.mjs
// 自包含：静态服务器随机隔离端口 + routeWebSocket 桩，finally 必关。
import { chromium } from 'playwright-core';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const SID = 's-qif-e2e';

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
  ws.send(JSON.stringify({ type: 'configData', config: '{"features":{}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
  ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: '队列空闲投递E2E' }], activeId: SID, folders: [] }));
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
  // drain 派发轮的标准收尾应答（busy{true} 即刻 + 终态对 250ms 后）
  const drainTurnScript = () => [
    { frame: { type: 'sessionBusy', sessionId: SID, busy: true } },
    { frame: { type: 'sessionBusy', sessionId: SID, busy: false }, delay: 250 },
    { frame: { type: 'done', sessionId: SID }, delay: 260 },
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

  // ══ P1 回复完成→空闲→队列自动投递（主钉） ══════════════════════════
  replyScript = holdBusyScript;
  await sendText('p1 first');
  await sleep(300);
  ok('P1.1 首条直发 + 服务端 busy 挂住', outboundUserFramesOf('p1 first').length === 1 && (await sidBusy()));
  await sendText('p1 queued');
  await sleep(300);
  ok('P1.2 busy 中入队不出线', outboundUserFramesOf('p1 queued').length === 0 && (await queuedItems()).length === 1);
  serverSends([{ type: 'sessionBusy', sessionId: SID, busy: false }, { type: 'done', sessionId: SID }]);
  replyScript = drainTurnScript;
  ok('P1.3 回复完成空闲后队列自动投递恰一条', await waitUntil(() => outboundUserFramesOf('p1 queued').length === 1, 4000),
    `frames=${outboundUserFramesOf('p1 queued').length}`);
  replyScript = null;
  await sleep(600); // 派发轮收尾
  ok('P1.4 投递轮照常收尾（busy 清）', !(await sidBusy()));
  ok('P1.5 队列已清空', (await queuedItems()).length === 0);

  // ══ P2 回复结束瞬间入队（竞态双序） ════════════════════════════════
  // (a) 入队先于终态帧处理：挂住 turn 占住 busy ⇒ Enter 落在「回复已完、终态帧
  //     在途」的窗口内入队，终态对 150ms 后才到场 ⇒ 必须照常投递（「空闲判定先于
  //     最后一条入队」的客户端形态 = 入队与空闲判定的到达序竞态）。
  await settle();
  replyScript = holdBusyScript;
  await sendText('p2 pre');
  await sleep(300);
  ok('P2.1 挂轮占住 busy', outboundUserFramesOf('p2 pre').length === 1 && (await sidBusy()));
  await sendText('p2 race');
  await sleep(150);
  ok('P2.2 竞态入队在队（终态帧未到）', outboundUserFramesOf('p2 race').length === 0 && (await queuedItems()).length === 1);
  serverSends([{ type: 'sessionBusy', sessionId: SID, busy: false }, { type: 'done', sessionId: SID }]);
  replyScript = drainTurnScript;
  ok('P2.3 终态帧迟到照常投递（竞态不丢）', await waitUntil(() => outboundUserFramesOf('p2 race').length === 1, 4000),
    `frames=${outboundUserFramesOf('p2 race').length}`);
  replyScript = null;
  await sleep(600);
  // (b) 终态已处理后再发送：会话已空闲 ⇒ 直发，不进队、无滞留。
  await sendText('p2 direct');
  await sleep(300);
  ok('P2.4 空闲后发送直发不出队', outboundUserFramesOf('p2 direct').length === 1 && (await queuedItems()).length === 0);
  serverSends([{ type: 'sessionBusy', sessionId: SID, busy: false }, { type: 'done', sessionId: SID }]);
  await sleep(400); // 直发轮终态收口（确认网被 busy{false} 撤）

  // ══ P3 多条排队依次投递 ════════════════════════════════════════════
  await settle();
  replyScript = holdBusyScript;
  await sendText('p3 m1');
  await sleep(300);
  await sendText('p3 m2');
  await sleep(200);
  await sendText('p3 m3');
  await sleep(200);
  ok('P3.1 两条入队不出线', outboundUserFramesOf('p3 m2').length === 0 && outboundUserFramesOf('p3 m3').length === 0 && (await queuedItems()).length === 2);
  serverSends([{ type: 'sessionBusy', sessionId: SID, busy: false }, { type: 'done', sessionId: SID }]);
  // m2 的派发轮：慢 turn（busy 挂 2.6s）——给「运行中不被抢跑」断言留观测窗。
  replyScript = () => [
    { frame: { type: 'sessionBusy', sessionId: SID, busy: true } },
    { frame: { type: 'sessionBusy', sessionId: SID, busy: false }, delay: 2600 },
    { frame: { type: 'done', sessionId: SID }, delay: 2610 },
  ];
  ok('P3.2 第一条终态后恰投 m2 一条', await waitUntil(() => outboundUserFramesOf('p3 m2').length === 1, 4000),
    `frames=${outboundUserFramesOf('p3 m2').length}`);
  await sleep(800); // m2 仍在跑（busy{true} 在场、终态 2.6s 后才到）
  ok('P3.3 m2 轮期间 m3 不被抢跑（运行中事实闸）', outboundUserFramesOf('p3 m3').length === 0,
    `frames=${outboundUserFramesOf('p3 m3').length}`);
  ok('P3.4 m2 轮运行中 busy 保留', await sidBusy());
  replyScript = drainTurnScript; // m3 的派发轮应答
  ok('P3.5 m2 终态后 m3 依次投递', await waitUntil(() => outboundUserFramesOf('p3 m3').length === 1, 6000),
    `frames=${outboundUserFramesOf('p3 m3').length}`);
  replyScript = null;
  await sleep(600);
  ok('P3.6 投递按队序（m2 先于 m3）', firstOutboundIndexOf('p3 m2') < firstOutboundIndexOf('p3 m3'));
  ok('P3.7 每条恰一次零重复派发', outboundUserFramesOf('p3 m1').length === 1 && outboundUserFramesOf('p3 m2').length === 1 && outboundUserFramesOf('p3 m3').length === 1);
  ok('P3.8 队列全清', (await queuedItems()).length === 0);

  // ══ R1 零新轮事实轮（压缩轮形态）——旧窗口闸永久滞留的回归捕获器 ════
  await settle();
  replyScript = holdBusyScript;
  await sendText('r1 pre'); // 直发锚：把 busy 挂住（其后两条才是排队件）
  await sleep(300);
  await sendText('r1 m1');
  await sleep(200);
  await sendText('r1 m2');
  await sleep(200);
  ok('R1.1 两条入队不出线（busy 中）', outboundUserFramesOf('r1 m1').length === 0 && outboundUserFramesOf('r1 m2').length === 0 && (await queuedItems()).length === 2);
  serverSends([{ type: 'sessionBusy', sessionId: SID, busy: false }, { type: 'done', sessionId: SID }]);
  // r1 m1 的「turn」= 压缩轮形态：compactStart/compactComplete 包裹，全程无
  // busy{true}、无流帧（引擎对压缩轮不发新轮事实）。sessionBusy{false} 落在压缩
  // 窗内 ⇒ 触发点被 compacting 闸跳过；compactComplete 在 busy 已清时补触发。
  replyScript = () => [
    { frame: { type: 'compactStart', sessionId: SID } },
    { frame: { type: 'sessionBusy', sessionId: SID, busy: false }, delay: 250 },
    { frame: { type: 'compactComplete', sessionId: SID, before: 1000, after: 400 }, delay: 300 },
    { frame: { type: 'done', sessionId: SID }, delay: 310 },
  ];
  ok('R1.2 m1 派发（压缩轮开场）', await waitUntil(() => outboundUserFramesOf('r1 m1').length === 1, 4000),
    `frames=${outboundUserFramesOf('r1 m1').length}`);
  await sleep(800); // 压缩窗 + 派发冷却窗内（r1 m1 刚派发）：不得抢跑
  ok('R1.3 压缩窗内不投递（compacting 闸 + 冷却窗）', outboundUserFramesOf('r1 m2').length === 0,
    `frames=${outboundUserFramesOf('r1 m2').length}`);
  // 冷却到界（派发 +2s）：零新轮事实 ⇒ 不受运行中闸挡，余件照常投递。
  // 旧布尔窗口被 r1 m1 的派发关死后无人开窗 ⇒ r1 m2 永远滞留（旧实现必红）。
  ok('R1.4 零新轮事实轮终态后余件照常投递（旧窗口闸必红）', await waitUntil(() => outboundUserFramesOf('r1 m2').length === 1, 6000),
    `frames=${outboundUserFramesOf('r1 m2').length}`);
  replyScript = null;
  await sleep(600);
  ok('R1.5 队列清空', (await queuedItems()).length === 0);

  // ══ R2 幻影派发（判重回执收口）后余件照常投递——旧窗口闸必红 ═══════
  await settle();
  replyScript = holdBusyScript;
  await sendText('r2 pre'); // 直发锚
  await sleep(300);
  await sendText('r2 m1');
  await sleep(200);
  await sendText('r2 m2');
  await sleep(200);
  serverSends([{ type: 'sessionBusy', sessionId: SID, busy: false }, { type: 'done', sessionId: SID }]);
  // r2 m1 的派发被服务端判重丢弃：不开 turn、零流帧，回 messageDuplicate + busy{false}
  // （busy-stuck-fix 精确腿收口 busy）。旧布尔窗口被 r2 m1 的派发关死后无人开窗 ⇒
  // r2 m2 永远滞留；新闸形零事实不受运行中闸挡，冷却到界即投。
  replyScript = (m) => [
    { frame: { type: 'messageDuplicate', sessionId: SID, clientMessageId: m.clientMessageId }, delay: 40 },
    { frame: { type: 'sessionBusy', sessionId: SID, busy: false }, delay: 45 },
  ];
  ok('R2.1 m1 派发（幻影轮开场）', await waitUntil(() => outboundUserFramesOf('r2 m1').length === 1, 4000),
    `frames=${outboundUserFramesOf('r2 m1').length}`);
  ok('R2.2 幻影派发收口后余件照常投递（旧窗口闸必红）', await waitUntil(() => outboundUserFramesOf('r2 m2').length === 1, 6000),
    `frames=${outboundUserFramesOf('r2 m2').length}`);
  replyScript = null;
  await sleep(600);
  ok('R2.3 收口后 busy 清', !(await sidBusy()));

  // ══ R3 stop 键清 busy 即投递（interrupted 帧缺席形态）——旧实现必红 ══
  await settle();
  replyScript = holdBusyScript;
  await sendText('r3 pre');
  await sleep(300);
  await sendText('r3 stop-q');
  await sleep(300);
  ok('R3.1 busy 挂住 + 队列项在场', (await sidBusy()) && (await queuedItems()).length === 1 && outboundUserFramesOf('r3 stop-q').length === 0);
  replyScript = null; // 桩不发 interrupted 帧 = 帧丢失形态；投递只能靠 stop 腿自身触发
  await sleep(2300); // 与上一次派发拉开冷却窗距离（触发形态确定性）
  await page.click('#stop-btn');
  ok('R3.2 stop 清 busy 后队列照常投递（旧实现必红）', await waitUntil(() => outboundUserFramesOf('r3 stop-q').length === 1, 6000),
    `frames=${outboundUserFramesOf('r3 stop-q').length}`);
  await sleep(600);
  ok('R3.3 stop 后 busy 清 + 队列清空', !(await sidBusy()) && (await queuedItems()).length === 0);

  // ══ E 静态钉：新闸形接缝 / 三条补腿在源面在场 ══════════════════════
  const mainSrc = await readFile(join(WEB, 'js', 'main.js'), 'utf8');
  const inputSrc = await readFile(join(WEB, 'js', 'input.js'), 'utf8');
  const chatSrc = await readFile(join(WEB, 'js', 'chat.js'), 'utf8');
  const stateSrc = await readFile(join(WEB, 'js', 'state.js'), 'utf8');
  ok('E1 派发冷却窗 + 运行中事实闸在 main.js', /QUEUE_DRAIN_COOLDOWN_MS/.test(mainSrc) && /lastQueueDispatchAt/.test(mainSrc) && /queueTurnFactAt/.test(mainSrc));
  ok('E2 新轮事实双挂点（sessionBusy{true} + 流帧族）', (mainSrc.match(/queueTurnFactAt\.set\(sid, Date\.now\(\)\)/g) || []).length >= 2,
    `sites=${(mainSrc.match(/queueTurnFactAt\.set\(sid, Date\.now\(\)\)/g) || []).length}`);
  ok('E3 冷却窗内触发 re-arm 到界（不丢触发）', /armQueueDrainTimer\(sid, dispatchedAt \+ QUEUE_DRAIN_COOLDOWN_MS - now\)/.test(mainSrc));
  ok('E4 state 槽接缝 scheduleQueueDrain 在场', /scheduleQueueDrain:\s*null/.test(stateSrc) && /state\.scheduleQueueDrain = scheduleQueueDrain/.test(mainSrc));
  ok('E5 stop 键补 drain 触发腿（input.js）', /state\.scheduleQueueDrain\?\.\(sid\)/.test(inputSrc));
  ok('E6 看门真死收口补 drain 触发腿（chat.js）', /state\.scheduleQueueDrain\?\.\(sid\)/.test(chatSrc));
  ok('E7 后台代理对账器补 drain 触发腿（main.js）', /clearBusy\(sid\);[\s\S]{0,220}scheduleQueueDrain\(sid\);/.test(mainSrc));
  ok('E8 旧布尔窗口令牌已退役', !/drainWindowUsed/.test(mainSrc));

  ok('E9 全程零 pageerror', pageErrors.length === 0, JSON.stringify(pageErrors));

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
