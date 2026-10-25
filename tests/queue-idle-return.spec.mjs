// queue-idle-return.spec.mjs — 修复批「排队消息空闲不投递复发·手机后台化场景」
// （2026-10-25 作者令逐字：「搜一下 我之前让修复消息队列的问题 修复了吗 现在我还是
// 发现存在 我在消息队列里的消息 你轮次结束进入空闲 但是消息不注入」，主用手机
// Safari）验收。
//
// 读码定谳的两处缺口（批报告 §1/§2，引擎零触碰）：
//   缺口 A（回前台无补触发腿）：iOS 后台/锁屏冻结 timers+WS——drain 的既有触发面
//     全部前提是「触发事件在页面活着时被处理」；冻结窗内帧挂起/丢失后无任何腿补
//     触发。修法 = visibilitychange→visible / pageshow(bfcache persisted) / WS 重连
//     三条补触发腿（sweepQueueDrains：纯重触发，闸面照旧裁决）。
//   缺口 B（断线窗丢终态帧 = 缺戳死锁形）：busy{true} 在场 + 终态对双丢 + 重连后
//     永无后续帧 ⇒ 投递未决闸（③）前提「有在跑事实 ∧ 无越界终态戳」永久成立（旧
//     知识）；六处权威终态戳全部依赖「帧到场」，对账器豁免活跃会话、看门被重连后
//     pong 活性挡住、确认网被 busy{true} 撤 ⇒ 队列永久滞留 = 报障本体。修法 = 重连
//     对账网（armQueueReconcileNet）：到点复核「无终态戳 ∧ 无流活动」才裁黑暗窗
//     终态丢失（settle 戳 + 清 stale busy + 补触发）；流帧/工具心跳/真终态任一到场
//     即撤网，一次一条语义零改动。
//   缺戳终态路审计收口：messageDuplicate / timeout 入 TERMINAL_MSG_TYPES（非活跃
//     会话帧此前被入口过滤器静默丢弃 ⇒ 判重精确腿戳/终态处理器饥饿）。
//
// 场景（真模块图 + routeWebSocket 桩服务端，harness 同 queue-idle-flush.spec.mjs 族）：
//   H1 可见补触发腿：空闲 + 队列非空的滞留形态，visibilitychange→visible 恰投一条
//      （旧实现无监听必红）。
//   H2 bfcache 补触发腿：pageshow persisted:true 恰投一条；persisted:false 不投
//      （负钉：普通加载不走补触发）。
//   H3 重连对账网：黑暗窗（终态对永不到场）+ 重连 ⇒ 网到点裁丢失、恰投一条、
//      busy 清（旧实现队列永久滞留必红）。
//   H4 对账网让位于活轮：重连后活轮（busy{true}+流帧）期间网不误裁（零投递），
//      活轮真终态后恰投一条（一次一条语义钉；无复核面的裸网必红）。
//   E  静态钉：三条补触发腿 / 对账网复核面 / TERMINAL 路由收口 / 投递未决闸③语义
//      钉不动。
//
// Run: node tests/queue-idle-return.spec.mjs
// 自包含：静态服务器随机隔离端口 + routeWebSocket 桩，finally 必关。
import { chromium } from 'playwright-core';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const SID = 's-qir-e2e';

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
  ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: '队列回归补触发E2E' }], activeId: SID, folders: [] }));
}

/** 服务端注入任意帧（模拟引擎事件流；走最新一条连接）。 */
const serverSends = (frames) => {
  for (const f of frames) currentWs.send(JSON.stringify(f));
};

const outboundUserFramesOf = (content) => conns.flatMap(c => c.inbound).filter(m => isUserContentFrame(m) && m.content === content);

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
  const setConfirmMs = (ms) => page.evaluate(async (v) => {
    (await import('/js/state.js')).default.turnConfirmMs = v;
  }, ms);
  /** 白盒滞留形态：直接把队列项放进内存队列（空闲 + 非空 = 补触发腿的输入形态；
   *  真实滞留流由 H3/H4 端到端覆盖）。 */
  const injectQueued = (text, cmid) => page.evaluate(({ sid, text, cmid }) => {
    return import('/js/state.js').then(m => {
      m.default.messageQueue[sid] = [{ id: Date.now(), text, clientMessageId: cmid, ts: Date.now(), attachments: [] }];
    });
  }, { sid: SID, text, cmid });
  const fireVisible = () => page.evaluate(() => { document.dispatchEvent(new Event('visibilitychange')); });
  const firePageshow = (persisted) => page.evaluate((p) => {
    window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: p }));
  }, persisted);
  // drain 派发轮的标准收尾应答（busy{true} 即刻 + 终态对 250ms 后）
  const drainTurnScript = () => [
    { frame: { type: 'sessionBusy', sessionId: SID, busy: true } },
    { frame: { type: 'sessionBusy', sessionId: SID, busy: false }, delay: 250 },
    { frame: { type: 'done', sessionId: SID }, delay: 260 },
  ];
  const holdBusyScript = () => [{ frame: { type: 'sessionBusy', sessionId: SID, busy: true } }];
  const waitUntil = async (cond, timeoutMs) => {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      if (await cond()) return true;
      await sleep(100);
    }
    return cond();
  };
  // 场景间静置：越过后一次派发的 2s 冷却窗 + 终态对收口。
  const settle = async () => {
    await sleep(2300);
    serverSends([{ type: 'sessionBusy', sessionId: SID, busy: false }, { type: 'done', sessionId: SID }]);
    await sleep(300);
  };

  ok('boot 后在线', conns.length > 0);
  await setConfirmMs(800); // 对账网预算加速（turnConfirmMs 旋钮与 turn-start 确认网同源）

  // ══ H1 可见补触发腿（缺口 A · 旧实现必红） ══════════════════════════
  await injectQueued('h1 sweep', 'h1cmid');
  await sleep(400); // 无触发面 ⇒ 滞留形态确定成形（旧实现此处之后永无投递）
  ok('H1.1 空闲滞留形态零投递（无补触发腿不投）', outboundUserFramesOf('h1 sweep').length === 0,
    `frames=${outboundUserFramesOf('h1 sweep').length}`);
  replyScript = drainTurnScript;
  await fireVisible();
  ok('H1.2 visibilitychange→visible 补触发恰投一条', await waitUntil(() => outboundUserFramesOf('h1 sweep').length === 1, 4000),
    `frames=${outboundUserFramesOf('h1 sweep').length}`);
  replyScript = null;
  await sleep(600); // 派发轮收尾

  // ══ H2 bfcache pageshow 补触发腿（缺口 A · 负钉 + 正钉） ═════════════
  await sleep(2000); // 越过 H1 派发冷却窗
  await injectQueued('h2 pageshow', 'h2cmid');
  await sleep(300);
  ok('H2.1 滞留形态零投递', outboundUserFramesOf('h2 pageshow').length === 0);
  await firePageshow(false); // 普通（非 bfcache）加载腿：不得投递（负钉）
  await sleep(600);
  ok('H2.2 pageshow persisted:false 不投（负钉）', outboundUserFramesOf('h2 pageshow').length === 0,
    `frames=${outboundUserFramesOf('h2 pageshow').length}`);
  replyScript = drainTurnScript;
  await firePageshow(true);
  ok('H2.3 pageshow persisted:true 补触发恰投一条', await waitUntil(() => outboundUserFramesOf('h2 pageshow').length === 1, 4000),
    `frames=${outboundUserFramesOf('h2 pageshow').length}`);
  replyScript = null;
  await sleep(600);

  // ══ H3 重连对账网：黑暗窗丢终态（缺口 B · 作者报障本体 · 旧实现必红） ══
  await settle();
  replyScript = holdBusyScript;
  await sendText('h3 m1');
  await sleep(300);
  ok('H3.1 直发锚 busy 挂住（busy{true} 事实在场）', outboundUserFramesOf('h3 m1').length === 1 && (await sidBusy()));
  await sendText('h3 m2');
  await sleep(300);
  ok('H3.2 busy 中入队不出线', outboundUserFramesOf('h3 m2').length === 0);
  replyScript = drainTurnScript; // m2 settle 后的派发轮收尾应答（一次性，等待消费）
  currentWs.close(); // 断线 ⇒ onclose ⇒ 自动重连（黑暗窗：终态对永不到场）
  ok('H3.3 重连后对账网裁黑暗窗终态丢失、恰投一条', await waitUntil(() => outboundUserFramesOf('h3 m2').length === 1, 15000),
    `conns=${conns.length} frames=${outboundUserFramesOf('h3 m2').length}`);
  await sleep(800);
  ok('H3.4 settle 后 busy 清（stale busy 收口）', !(await sidBusy()));
  replyScript = null;
  await sleep(1000);

  // ══ H4 对账网让位于活轮（一次一条语义钉） ══════════════════════════
  await settle();
  replyScript = holdBusyScript;
  await sendText('h4 m1');
  await sleep(300);
  await sendText('h4 m2');
  await sleep(300);
  ok('H4.1 busy 挂住 + 队列项在场', (await sidBusy()) && outboundUserFramesOf('h4 m2').length === 0);
  replyScript = null;
  currentWs.close();
  // 重连后模拟「活轮」：busy{true} + 流帧（sessionStreamActivity 复核面的事实源），
  // 流持续 >1.2s（> 对账网 800ms 预算）——无复核面的裸网会在活轮中途误裁误投。
  await waitUntil(() => conns.length >= 2, 10000);
  const reconnected = conns.length >= 2;
  if (reconnected) {
    serverSends([{ type: 'sessionBusy', sessionId: SID, busy: true }]);
    for (let i = 0; i < 4; i++) {
      await sleep(300);
      serverSends([{ type: 'textDelta', sessionId: SID, delta: 'h4 轮仍在产出一' + i }]);
    }
  }
  ok('H4.2 活轮流帧期间对账网不误裁（零抢跑）', !reconnected || outboundUserFramesOf('h4 m2').length === 0,
    `frames=${outboundUserFramesOf('h4 m2').length}`);
  replyScript = drainTurnScript; // m2 派发轮应答
  serverSends([{ type: 'sessionBusy', sessionId: SID, busy: false }, { type: 'done', sessionId: SID }]);
  ok('H4.3 活轮真终态后恰投一条（一次一条语义钉）', await waitUntil(() => outboundUserFramesOf('h4 m2').length === 1, 6000),
    `frames=${outboundUserFramesOf('h4 m2').length}`);
  replyScript = null;
  await sleep(600);

  // ══ E 静态钉：补触发腿 / 对账网复核面 / 路由收口 / 语义钉不动 ═══════
  const mainSrc = await readFile(join(WEB, 'js', 'main.js'), 'utf8');
  const wsSrc = await readFile(join(WEB, 'js', 'ws.js'), 'utf8');
  ok('E1 可见/bfcache 补触发腿在 main.js', /addEventListener\('visibilitychange', \(\) => \{\s*\n\s*if \(document\.visibilityState === 'visible'\) sweepQueueDrains\(\)/.test(mainSrc)
    && /addEventListener\('pageshow', \(e\) => \{ if \(e && e\.persisted\) sweepQueueDrains\(\);?\s*\}\)/.test(mainSrc));
  ok('E2 WS 重连补触发 + 对账网武装在 onReconnect', /sweepQueueDrains\(\);[\s\S]{0,200}armQueueReconcileNet\(qsid\)/.test(mainSrc));
  ok('E3 对账网复核面（终态戳/流活动/驻留 busy 三条件）', /lastTerminalAt\[sid\] \|\| 0\) > armedAt\) return/.test(mainSrc)
    && /sessionStreamActivity\[sid\] \|\| 0\) > armedAt\) return/.test(mainSrc)
    && /state\.lastTerminalAt\[sid\] = Date\.now\(\);\s*\n\s*clearBusy\(sid\);\s*\n\s*scheduleQueueDrain\(sid\);/.test(mainSrc));
  ok('E4 新派发撤对账网（armTurnConfirm 内）', /cancelTurnConfirm\(sid\);[\s\S]{0,300}cancelQueueReconcileNet\(sid\);/.test(mainSrc));
  ok('E5 messageDuplicate/timeout 入 TERMINAL 路由集（缺戳终态路收口）', /'timeout', 'messageDuplicate',/.test(wsSrc));
  ok('E6 投递未决闸③语义钉不动（一次一条）', /unresolvedFactAt > dispatchedAt && termStampedAt <= dispatchedAt\) return;/.test(mainSrc));
  ok('E7 补触发清扫 = 纯重触发（闸面共用于 armQueueDrainTimer）', /function sweepQueueDrains\(\)[\s\S]{0,200}scheduleQueueDrain\(sid\);/.test(mainSrc));

  ok('E8 全程零 pageerror', pageErrors.length === 0, JSON.stringify(pageErrors));

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
