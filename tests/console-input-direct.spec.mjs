// console-input-direct.spec.mjs — 控制台输入「排队改直通」验收（queuedirect 批，
// 作者令 2026-10-28 11:1x「取消我们的这个输入排队功能（不删代码）……改为，直接输入
// 给你」；11:14 补充裁定「应该不会打断你的 llm 调用吧？效果就像是我们现在的立即发
// 送一样」）。
//
// 被测面 = **真发送链**（真实 web 树 + 真模块图 + 真用户动作，WS 层 = routeWebSocket
// 桩服务端；桩 holdTurn 模式 = 受理帧只回 sessionBusy{true} 不发终态，模拟引擎持轮，
// f1-send-queue.spec.mjs 同款机械读数）：
//
//   D（direct = 缺省配置，configData 不带 console 键）：
//     ① 持轮会话中再发消息 ⇒ **立即出线** `type:'immediateInput'` 帧（「立即发送」
//        等价腿：content + clientMessageId + sessionId），零本地入队、零排队条、
//        乐观气泡即上屏（data-msg-id = 幂等键）。
//     ② 引擎在飞事实保持（桩 busy{true} 后无终态帧——直通帧不打断持轮，只并入）。
//   Q（queue 回归：configData 带 `"console":{"inputMode":"queue"}`）：
//     ③ 持轮会话中再发消息 ⇒ **零出线**、入本地队（localStorage 队列项 + 幂等键
//        钉死）、呈现面 = 排队条（#queue-bar.visible）、会话流内零乐观气泡。
//     ④ done 终态 → drain 逐条补发：恰一帧默认用户帧（无 type）、id = 入队钉死
//        幂等键、队列排空（旧行为逐字回归——排队实现一行未删）。
//   S 源面静态钉：featureFlags.consoleInputMode 缺省 direct；input.js 直通腿走
//     immediateInput + dispatchQueuedItemNow；queue 腿（queueMessage）保留在场。
//
// Run: node tests/console-input-direct.spec.mjs
// 自包含：静态服务器随机隔离端口 + routeWebSocket 桩，finally 必关。
import { chromium } from 'playwright-core';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const SID = 's-qdirect-e2e';

let failures = 0;
function ok(name, cond, extra = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
}
const sleep = ms => new Promise(r => setTimeout(r, ms));
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2' };

// ── 桩服务端状态 ────────────────────────────────────────────────────
let currentWs = null;
let holdTurn = false;          // true = 受理帧只回 sessionBusy{true}，不发终态（引擎持轮形态）
let configJson = '{"features":{}}'; // 每场景重设（direct 缺省 / queue 显式）
const conns = [];              // 每条连接的入站帧日志

const isUserContentFrame = m => !m.type && typeof m.content === 'string';
const immFramesOf = (content) => conns.flatMap(c => c.inbound)
  .filter(m => m.type === 'immediateInput' && m.content === content);
const userFramesOf = (content) => conns.flatMap(c => c.inbound)
  .filter(m => isUserContentFrame(m) && m.content === content);
const anyFrameOf = (content) => conns.flatMap(c => c.inbound)
  .filter(m => m.content === content);

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
    if (isUserContentFrame(m)) {
      // 默认用户帧受理（direct 直通腿的附件形 / queue drain 腿都走这里）。
      ws.send(JSON.stringify({ type: 'sessionBusy', sessionId: m.sessionId, busy: true }));
      if (holdTurn) return; // 持轮：终态扣发
      setTimeout(() => {
        try {
          ws.send(JSON.stringify({ type: 'sessionBusy', sessionId: m.sessionId, busy: false }));
          ws.send(JSON.stringify({ type: 'done', sessionId: m.sessionId, model: 'mock-llm' }));
        } catch { /* 连接已被测试关闭 */ }
      }, 120);
    }
    // immediateInput 帧受理：引擎侧 busy ⇒ 入 pendingImmediateInputs（回合内最近
    // 边界注入）——桩面只回 busy{true}，不回终态（直通不打断持轮）。
    if (m.type === 'immediateInput') {
      ws.send(JSON.stringify({ type: 'sessionBusy', sessionId: m.sessionId, busy: true }));
    }
  });
  // 引导帧（保持应用安静 + 挂上被测会话；config 键 = 每场景注入）
  ws.send(JSON.stringify({ type: 'configData', config: configJson, configured: true, onboarding: 'done', models: [], defaults: {} }));
  ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: '排队直通E2E' }], activeId: SID, folders: [] }));
}

/** 引擎持轮窗内「回合真结束」： spont 终态帧族（queue 模式 drain 的唯一挂点）。 */
function releaseTurn() {
  if (!currentWs) return;
  currentWs.send(JSON.stringify({ type: 'sessionBusy', sessionId: SID, busy: false }));
  currentWs.send(JSON.stringify({ type: 'done', sessionId: SID, model: 'mock-llm' }));
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
try {
  // ══ 场景 D：direct（缺省）——busy 发送立即出线 ══════════════════════════
  {
    configJson = '{"features":{}}'; // 不带 console 键 = direct 缺省
    conns.length = 0;
    holdTurn = true;
    const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
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

    const bubbleCount = (txt) => page.evaluate((t) => {
      return [...document.querySelectorAll('.row.user .bubble.user')]
        .filter(b => (b.textContent || '').includes(t)).length;
    }, txt);
    const queuedItems = () => page.evaluate(() => {
      try {
        const raw = localStorage.getItem(Object.keys(localStorage).find((k) => k.includes('message_queue')));
        return Object.values(JSON.parse(raw || '{}')).flat();
      } catch { return []; }
    });
    const busyMirror = () => page.evaluate(async (sid) => {
      const s = await import('/js/state.js');
      return s.default.busySessionIds.has(sid);
    }, SID);

    // ① 开轮：首条消息出线并被持轮（busy 镜像建立）
    await page.fill('#input', '持轮首条');
    await page.keyboard.press('Enter');
    await sleep(700);
    ok('D1 持轮首条出线且进入持轮态', userFramesOf('持轮首条').length === 1 && (await busyMirror()) === true,
      `frames=${userFramesOf('持轮首条').length} busy=${await busyMirror()}`);

    // ② busy 中再发一条 ⇒ direct 直通：immediateInput 立即出线
    await page.fill('#input', 'direct 直通消息');
    await page.keyboard.press('Enter');
    await sleep(500);
    const imm = immFramesOf('direct 直通消息');
    ok('D2a busy 发送立即出线恰一帧 immediateInput', imm.length === 1,
      `immFrames=${imm.length} totalAny=${anyFrameOf('direct 直通消息').length}`);
    ok('D2b 帧形 = 「立即发送」等价腿（content+幂等键+会话）',
      imm.length === 1 && typeof imm[0].clientMessageId === 'string' && imm[0].clientMessageId.length > 0
        && imm[0].sessionId === SID,
      JSON.stringify(imm[0] ? { id: imm[0].clientMessageId, sid: imm[0].sessionId } : null));
    ok('D2c 不走默认用户帧腿（直通≠普通 UserInput——引擎侧才分边界）', userFramesOf('direct 直通消息').length === 0);
    ok('D2d 零本地入队（排队功能不承接直通消息）', (await queuedItems()).length === 0,
      JSON.stringify(await queuedItems()));
    ok('D2e 乐观气泡即上屏（恰一份）', (await bubbleCount('direct 直通消息')) === 1,
      `bubbles=${await bubbleCount('direct 直通消息')}`);
    ok('D2f 排队条保持隐藏', await page.evaluate(() => !document.getElementById('queue-bar').classList.contains('visible')));
    ok('D2g 引擎持轮事实不被直通打断（busy 镜像保持）', (await busyMirror()) === true);
    ok('D2h 输入框已清空（发送语义闭环）', await page.evaluate(() => document.getElementById('input').value === ''));

    // ③ 直通后再发第二条（连击面：两条都各自立即出线）
    await page.fill('#input', 'direct 第二条');
    await page.keyboard.press('Enter');
    await sleep(500);
    ok('D3 连击第二条同样立即出线', immFramesOf('direct 第二条').length === 1,
      `immFrames=${immFramesOf('direct 第二条').length}`);
    ok('D4 全程零 pageerror', pageErrors.length === 0, JSON.stringify(pageErrors));
    await ctx.close();
  }

  // ══ 场景 Q：queue（显式配置）——旧行为逐字回归 ══════════════════════════
  {
    configJson = '{"features":{},"console":{"inputMode":"queue"}}';
    conns.length = 0;
    holdTurn = true;
    const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
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
    await sleep(1500);

    const bubbleCount = (txt) => page.evaluate((t) => {
      return [...document.querySelectorAll('.row.user .bubble.user')]
        .filter(b => (b.textContent || '').includes(t)).length;
    }, txt);
    const queuedItems = () => page.evaluate(() => {
      try {
        const raw = localStorage.getItem(Object.keys(localStorage).find((k) => k.includes('message_queue')));
        return Object.values(JSON.parse(raw || '{}')).flat();
      } catch { return []; }
    });
    const busyMirror = () => page.evaluate(async (sid) => {
      const s = await import('/js/state.js');
      return s.default.busySessionIds.has(sid);
    }, SID);

    await page.fill('#input', '持轮首条Q');
    await page.keyboard.press('Enter');
    await sleep(700);
    ok('Q1 持轮首条出线且进入持轮态', userFramesOf('持轮首条Q').length === 1 && (await busyMirror()) === true);

    // ② busy 中再发一条 ⇒ queue 旧行为：零出线、入队、排队条
    await page.fill('#input', 'queued 旧轨消息');
    await page.keyboard.press('Enter');
    await sleep(500);
    ok('Q2a busy 发送零出线（不直通）', anyFrameOf('queued 旧轨消息').length === 0,
      `frames=${anyFrameOf('queued 旧轨消息').length}`);
    const q2 = await queuedItems();
    ok('Q2b 入本地队且幂等键入队钉死', q2.length === 1 && typeof q2[0].clientMessageId === 'string' && q2[0].clientMessageId.length > 0,
      JSON.stringify(q2.map(i => ({ id: i.clientMessageId }))));
    const pinnedId = q2[0]?.clientMessageId;
    ok('Q2c 呈现面 = 排队条（#queue-bar.visible）',
      await page.evaluate(() => document.getElementById('queue-bar').classList.contains('visible')));
    ok('Q2d 会话流内零乐观气泡（排队回退批形态保持）', (await bubbleCount('queued 旧轨消息')) === 0,
      `bubbles=${await bubbleCount('queued 旧轨消息')}`);

    // ③ done 终态 → drain 补发（旧排队语义的完整闭环）
    holdTurn = false;
    releaseTurn();
    await sleep(1000);
    const drained = userFramesOf('queued 旧轨消息');
    ok('Q3a done 后 drain 补发恰一帧（默认用户帧）', drained.length === 1,
      `frames=${drained.length}`);
    ok('Q3b drain 帧 id = 入队钉死的幂等键（#569 同 id 轨）',
      drained.length === 1 && drained[0].clientMessageId === pinnedId,
      `frame=${drained[0]?.clientMessageId} pinned=${pinnedId}`);
    ok('Q3c 队列排空', (await queuedItems()).length === 0, JSON.stringify(await queuedItems()));
    ok('Q3d 排队条收起', await page.evaluate(() => !document.getElementById('queue-bar').classList.contains('visible')));
    ok('Q4 全程零 pageerror', pageErrors.length === 0, JSON.stringify(pageErrors));
    await ctx.close();
  }

  // ══ 源面静态钉 ════════════════════════════════════════════════════════
  {
    const flagsSrc = await readFile(join(WEB, 'js', 'featureFlags.js'), 'utf8');
    ok('S1 featureFlags 导出 consoleInputMode（缺省 direct）',
      /export function consoleInputMode\(\)/.test(flagsSrc) && /'direct'/.test(flagsSrc) && /console\?\.inputMode/.test(flagsSrc));
    const src = await readFile(join(WEB, 'js', 'input.js'), 'utf8');
    ok('S2 直通腿挂 config 读取（consoleInputMode 进 send 链）',
      /consoleInputMode\(\) !== 'direct'/.test(src));
    ok('S3 直通腿 = dispatchQueuedItemNow 共用派发台（quiesce/newTurn 关 + wire:immediate）',
      /dispatchQueuedItemNow\(v\.sessionId, directItem, \{ quiesce: false, newTurn: false, wire: 'immediate' \}\)/.test(src));
    ok('S4 排队旧轨保留（queueMessage 在场 = 开关切回即旧行为）',
      /queueMessage\(v, text, v\.pendingAttachments\)/.test(src));
    ok('S5 skill 忙时直通腿在场（skillName 直投）',
      /dispatchQueuedItemNow\(v\.sessionId, item, \{ quiesce: false, newTurn: false \}\)/.test(src));
  }

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
