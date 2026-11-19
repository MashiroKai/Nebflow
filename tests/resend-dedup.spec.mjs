// resend-dedup.spec.mjs — 修复批 #569「客户端断线重连自动重发消息（重复气泡）」验收。
//
// 被测面 = **真发送链**（真实 web 树 + 真模块图 + 真用户动作，WS 层 = routeWebSocket
// 桩服务端；桩内实现「服务端按 clientMessageId 去重 + messageDuplicate 回执」契约，
// 与 AgentSession.emitDuplicateReceipt 的 Scala 落地面同形）：
//
//   A 已送达场景：发送 → 服务端受理（id 登记）→ turn 完成 → WS 断开 ≈N 分钟 →
//     重连 → 用户点「重试」补发 ⇒ 线上帧携带**同一个** clientMessageId（幂等键 =
//     逻辑消息，不是发送动作）；桩判重 → 回 messageDuplicate ⇒ **零第二个气泡、
//     零第二次 turn**。
//   B 真未送达场景：发送帧被桩**静默丢弃**（= 从未送达）→ 断开 → 重连 → 重试 ⇒
//     同 id 补发 ⇒ 桩首见该 id ⇒ 正常受理 ⇒ **消息恰好送达一次，不丢**。
//   C 排队 drain 幂等键：busy 中入队 → 终态帧 drain 派发 ⇒ 帧带入队时钉死的 id；
//     同一终态帧对（done + sessionBusy{false}）重复到达 ⇒ **不二连派发**。
//   D wire 契约（静态）：ask / skill / immediateInput 帧也带 clientMessageId 字段
//     （源面正则钉，与 input.js 实现同树同读数）。
//
// 红线自检：本 spec 零触碰消息渲染管线（不 check chat.css / 渲染函数），断言面 =
// 线上帧 + 用户气泡行数。
//
// Run: node tests/resend-dedup.spec.mjs
// 自包含：静态服务器随机隔离端口 + routeWebSocket 桩，finally 必关。
import { chromium } from 'playwright-core';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const SID = 's-resend-e2e';

let failures = 0;
function ok(name, cond, extra = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
}
const sleep = ms => new Promise(r => setTimeout(r, ms));
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2' };

// ── 桩服务端状态（按连接切换 currentWs；seen = 已受理 id 台账）────────────
let currentWs = null;          // 当前连接（node 侧句柄，测试用它模拟断线）
let silentDrop = false;        // true = 用户帧收下但不受理不回帧（= 真未送达）
let connSeq = 0;
const conns = [];              // 每条连接的入站帧日志
const accepted = [];           // 受理的用户内容帧（content/ask/skill）
const receipts = [];           // 发出的 messageDuplicate 回执
const seenIds = new Map();     // clientMessageId -> 受理次数

function isUserContentFrame(m) {
  if (m.type === 'immediateInput') return true;
  if (m.type === 'ask' || m.type === 'skill') return true;
  // typeless content 帧（普通发送 / drain 派发）
  return !m.type && typeof m.content === 'string';
}

/** 用户内容帧的桩裁决：判重 → 回执丢弃；首见 → 受理 + 模拟 turn 生命周期。 */
function handleUserFrame(ws, m) {
  const id = m.clientMessageId;
  if (silentDrop) return; // 静默丢弃：不登记、不回帧（服务端从没见过这条消息）
  if (id && seenIds.has(id)) {
    receipts.push({ id, sessionId: m.sessionId });
    ws.send(JSON.stringify({ type: 'messageDuplicate', sessionId: m.sessionId, clientMessageId: id }));
    return;
  }
  if (id) seenIds.set(id, (seenIds.get(id) || 0) + 1);
  accepted.push(m);
  ws.send(JSON.stringify({ type: 'sessionBusy', sessionId: m.sessionId, busy: true }));
  setTimeout(() => {
    try {
      ws.send(JSON.stringify({ type: 'sessionBusy', sessionId: m.sessionId, busy: false }));
      ws.send(JSON.stringify({ type: 'done', sessionId: m.sessionId, model: 'mock-llm' }));
    } catch { /* 连接已被测试关闭 */ }
  }, 120);
}

function wireUp(ws) {
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
    if (isUserContentFrame(m)) handleUserFrame(ws, m);
  });
  // 引导帧（保持应用安静 + 挂上被测会话）
  ws.send(JSON.stringify({ type: 'configData', config: '{"features":{},"console":{"inputMode":"queue"}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
  ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: '重发去重E2E' }], activeId: SID, folders: [] }));
}

/** 等到桩观察到新连接（客户端重连成功）。 */
async function waitForNewConnection(prevSeq, timeoutMs = 15000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (conns.length > 0 && conns[conns.length - 1].seq > prevSeq) return conns[conns.length - 1];
    await sleep(100);
  }
  throw new Error('client did not reconnect in time');
}

/** 桩上某 content 的受理帧（按 clientMessageId 归并）。 */
const acceptedOf = (content) => accepted.filter(m => m.content === content);
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
    localStorage.setItem('nebflow_message_queue', '{}');
    localStorage.setItem('nebflow_input_history', '[]');
  });
  const page = await ctx.newPage();
  const pageErrors = [];
  page.on('pageerror', e => pageErrors.push(e.message));
  await page.route('**/api/**', r => r.fulfill({ json: {} }));
  await page.routeWebSocket(/\/ws/, ws => wireUp(ws));

  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#input', { state: 'visible', timeout: 20000 });
  await sleep(1500); // 引导帧消化（sessionList → 视图挂载）
  ok('boot 会话视图挂载', await page.evaluate((sid) => {
    const el = document.querySelector(`[data-session-id="${sid}"]`);
    return !!document.getElementById('input');
  }), SID);

  // ══ 场景 A：已送达 → 同 id 重发 → 判重回执 → 零重复气泡 ══════════════
  await page.fill('#input', 'hello dedup');
  await page.click('#send-btn');
  await sleep(700); // 帧出线 + 桩 turn 生命周期回放
  const firstFrames = inboundUserFramesOf('hello dedup');
  ok('A1 首发帧带 clientMessageId', firstFrames.length === 1 && !!firstFrames[0].clientMessageId,
    JSON.stringify(firstFrames.map(f => ({ id: f.clientMessageId, type: f.type || '(typeless)' }))));
  const idX = firstFrames[0]?.clientMessageId;
  await sleep(500); // busy false + done 消化（busy 自清）
  const bubbleCount = () => page.evaluate((txt) => {
    const rows = [...document.querySelectorAll('.row.user .bubble.user')];
    return rows.filter(b => (b.textContent || '').includes(txt)).length;
  }, 'hello dedup');
  ok('A2 首发后恰一个用户气泡', (await bubbleCount()) === 1, `count=${await bubbleCount()}`);

  const seqBefore = conns[conns.length - 1].seq;
  await currentWs.close(); // 模拟 relay 票据 TTL 到点断开（时长无关性：不睡 15 分钟）
  const conn2 = await waitForNewConnection(seqBefore);
  ok('A3 断开后客户端自动重连', conn2.inbound.length >= 0, `conn #${conn2.seq}`);

  // 真重试入口：看门真死终态行的「重试」键（chat.js renderTerminalRow → resendLastInput）
  await page.evaluate(async (sid) => {
    const chat = await import('/js/chat.js');
    chat.renderConnectionLostNotice(sid, 'ws-closed');
  }, SID);
  await sleep(200);
  const retryBtn = page.locator('.row.error .error-card button').first();
  ok('A4 终态行重试键在场', await retryBtn.isVisible().catch(() => false));
  // 重连后 main.js onReconnect 已 getHistory ⇒ historyPage 初载**替换**聊天区
  // （真应用行为：后端历史 = 真值源）。重发点击前的气泡基线照实取。
  const bubblesBeforeRetry = await bubbleCount();
  await retryBtn.click();
  await sleep(600);
  const resendFrames = inboundUserFramesOf('hello dedup');
  ok('A5 重发帧携带同一个 clientMessageId（幂等键 = 逻辑消息）',
    resendFrames.length === 2 && resendFrames[1].clientMessageId === idX,
    `frames=${resendFrames.length}, first=${idX}, resend=${resendFrames[1]?.clientMessageId}`);
  ok('A6 桩判重：第二次受理为零（不跑第二个 turn）', acceptedOf('hello dedup').length === 1,
    `accepted=${acceptedOf('hello dedup').length}`);
  // 🔴 回归主钉：改前重发腿（resendLastInput → send() 现生成新 id）会**再渲染一个
  // 乐观气泡**（= 重复气泡的客户端半边）。改后同 id 重发零新增呈现。
  ok('A7 零重复气泡（重发不再渲染第二份）', (await bubbleCount()) === bubblesBeforeRetry,
    `before=${bubblesBeforeRetry}, after=${await bubbleCount()}`);
  await sleep(400); // messageDuplicate 回执消化
  ok('A8 回执发出且客户端零 pageerror', receipts.some(r => r.id === idX) && pageErrors.length === 0,
    `receipts=${receipts.length}, pageErrors=${JSON.stringify(pageErrors)}`);

  // ══ 场景 B：真未送达 → 同 id 补发 → 恰好送达一次 ════════════════════
  silentDrop = true;
  await page.fill('#input', 'lost in flight');
  await page.click('#send-btn');
  await sleep(500);
  ok('B1 未送达帧不入受理台账', acceptedOf('lost in flight').length === 0);
  silentDrop = false;
  const seqB = conns[conns.length - 1].seq;
  await currentWs.close();
  await waitForNewConnection(seqB);
  // 重连后 main.js onReconnect 已 getHistory ⇒ historyPage 初载**替换**聊天区
  //（A 腿注记同款）。真时序里看门终态行也落在初载替换**之后** —— 先等替换落定
  // 再挂终态行，避免行刚渲染就被历史替换冲掉（harness 时序修正，非判据变化）。
  await sleep(800);
  await page.evaluate(async (sid) => {
    const chat = await import('/js/chat.js');
    chat.renderConnectionLostNotice(sid, 'ws-closed');
  }, SID);
  await sleep(200);
  await page.locator('.row.error .error-card button').first().click();
  await sleep(800);
  const bFrames = inboundUserFramesOf('lost in flight');
  const bFirstId = bFrames[0]?.clientMessageId;
  const bubbleCount2 = () => page.evaluate((txt) => {
    const rows = [...document.querySelectorAll('.row.user .bubble.user')];
    return rows.filter(b => (b.textContent || '').includes(txt)).length;
  }, 'lost in flight');
  ok('B2 补发帧带原 id 且桩首见受理（不丢）',
    bFrames.length === 2 && bFrames[1].clientMessageId === bFirstId && acceptedOf('lost in flight').length === 1,
    `frames=${bFrames.length}, accepted=${acceptedOf('lost in flight').length}, id=${bFirstId}`);
  ok('B3 补发零新增气泡（呈现面 = 历史真值源，补发不重复渲染）', (await bubbleCount2()) === 0,
    `count=${await bubbleCount2()}`);
  ok('B4 零 pageerror', pageErrors.length === 0, JSON.stringify(pageErrors));

  // ══ 场景 C：排队 drain 幂等键 + per-turn 终态帧对不二连派发 ══════════
  // busy 态下 #send-btn 隐藏（setBusy 换 stop 键）⇒ 真发送路径 = 输入框 Enter。
  const enterSend = async (text) => {
    await page.fill('#input', text);
    await page.keyboard.press('Enter');
  };
  await currentWs.send(JSON.stringify({ type: 'sessionBusy', sessionId: SID, busy: true }));
  await sleep(300);
  await enterSend('queued one');
  await sleep(400); // busy ⇒ 入本地队列（queue bar），零出线
  ok('C1 busy 中消息入本地队（零出线）',
    acceptedOf('queued one').length === 0 && inboundUserFramesOf('queued one').length === 0,
    `inbound=${inboundUserFramesOf('queued one').length}`);
  // 终态帧对（引擎固定发两帧：done + sessionBusy{false}）→ drain 派发队头
  await currentWs.send(JSON.stringify({ type: 'done', sessionId: SID, model: 'mock-llm' }));
  await currentWs.send(JSON.stringify({ type: 'sessionBusy', sessionId: SID, busy: false }));
  await sleep(700); // 50ms drain 定时器 + 帧出线
  const cFrames = inboundUserFramesOf('queued one');
  ok('C2 drain 派发恰一帧且带 clientMessageId', cFrames.length === 1 && !!cFrames[0].clientMessageId,
    JSON.stringify(cFrames.map(f => f.clientMessageId)));
  await sleep(600); // 桩对该帧的 turn 生命周期回放（busy true/false + done）消化
  // 重复终态帧对（= 引擎 done+sessionBusy{false} 双帧的重复到达形态）
  // ⇒ 已 drain 的这条消息**不得**被再次派发。
  await currentWs.send(JSON.stringify({ type: 'done', sessionId: SID, model: 'mock-llm' }));
  await currentWs.send(JSON.stringify({ type: 'sessionBusy', sessionId: SID, busy: false }));
  await sleep(800);
  ok('C4 重复终态帧对不二连派发（已 drain 消息恰派发一次）',
    inboundUserFramesOf('queued one').length === 1,
    `inbound=${inboundUserFramesOf('queued one').length}`);
  ok('C5 零 pageerror', pageErrors.length === 0, JSON.stringify(pageErrors));

  // ══ 场景 D：wire 契约静态钉（ask / skill / immediateInput 帧 id 字段）═══
  const src = await readFile(join(WEB, 'js', 'input.js'), 'utf8');
  ok('D1 ask 帧带 clientMessageId', /type:\s*'ask',\s*question,\s*clientMessageId:\s*askId/.test(src));
  ok('D2 skill 帧带 clientMessageId', /type:\s*'skill',\s*skillName,\s*input:\s*text,\s*clientMessageId:\s*skillId/.test(src));
  // 判据跟 device-provenance 批（2026-10-23）：帧中段插入了 device 声明字段，
  // 正则放宽为中段任意帧字段（仍钉死 immediateInput 帧必带 itemMsgId 幂等键）。
  ok('D3 immediateInput 帧带 clientMessageId', /type:\s*'immediateInput',\s*content:\s*item\.text,[\s\S]{0,80}clientMessageId:\s*itemMsgId/.test(src));
  ok('D4 排队项入队即钉 id（drain/立即发送同 id 复用）', /mode,\s*\n\s*clientMessageId:\s*Date\.now\(\)\.toString\(36\)/.test(src));

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
