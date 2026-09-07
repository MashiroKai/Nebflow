#!/usr/bin/env node
// smoke-hardrecovery.mjs — 引擎活挂硬恢复反事实冒烟（2026-09-07 设计验收
// T1/T3/T4/T10，隔离实例专用——绝不指向 8080 宿主）。
//
// 三场景（mock Anthropic-protocol LLM 驱动真实引擎）：
//   A. SessionKick（T1/T3）：黑洞 SSE 楔死 turn → 第二条用户消息触发 kick →
//      transport abort 解楔（mock 侧观测 socket 中断）→ 整 turn 重发（请求体
//      含两条消息全文）→ 会话真实离开 Processing。
//   B. 注入合批（T10）：楔死中 3 条 immediateInput 排队（idle 窗口内不触发
//      kick）→ kick 消息打破 → 下一轮**单个**请求携带全部 3 条注入（token
//      反事实：不存在只含 1/2 条注入的串行请求）→ 单次回复完成。
//   C. watcher 分级（T4）：加速阈值（stuckThresholdMs 配置位）→ taskStuck 帧
//      action 序列 halt → hard-abort → abort 后恢复完成；req#1 socket 中断
//      时刻与 hard-abort 帧对齐。
//
// 前置（编排脚本 /tmp/qa-hardrec-run.sh）：mock LLM 本脚本自起（MOCK_PORT，
// 默认 8791——必须避开 8080）；实例 home 的 nebflow.json 指向 mock 并设
// stuckThresholdMs=12000；实例以 -Dnebflow.hardRecovery.kickIdleSec=8 启动。
//
// Run:
//   NEBFLOW_URL=http://127.0.0.1:8095 NEBFLOW_HOME_DIR=/tmp/qa-hardrec/home \
//     node scripts/smoke-hardrecovery.mjs
// Env: NEBFLOW_URL / NEBFLOW_HOME_DIR / MOCK_PORT / SMOKE_SCENARIOS(A,B,C)
//      / SMOKE_MAX_MS(默认 360000)

import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import http from 'node:http';

// 沙箱下 undici 对 `localhost` 的 WS 解析会挂死——强制 127.0.0.1。
const URL_BASE = (process.env.NEBFLOW_URL || 'http://127.0.0.1:8095').replace('localhost', '127.0.0.1');
const HOME = process.env.NEBFLOW_HOME_DIR;
const MOCK_PORT = parseInt(process.env.MOCK_PORT || '8791', 10);
const SCENARIOS = (process.env.SMOKE_SCENARIOS || 'A,B,C').split(',');
const MAX_MS = parseInt(process.env.SMOKE_MAX_MS || '360000', 10);
if (!HOME) {
  console.error('FAIL  NEBFLOW_HOME_DIR is required (isolated instance home, never ~/.nebflow)');
  process.exit(1);
}
const TOKEN = JSON.parse(readFileSync(join(HOME, 'auth.json'), 'utf8'));

const KICK_IDLE_SEC = 8; // 编排脚本必须以同值 -D 启动实例
const IDLE_WAIT_MS = (KICK_IDLE_SEC + 2) * 1000; // 过 kick 窗口（< 12s watcher 阈值）

let failed = 0;
function check(name, ok, extra = '') {
  if (!ok) failed++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const deadline = Date.now() + MAX_MS;
function remaining() { return deadline - Date.now(); }

// ── mock Anthropic-protocol LLM（模式可切换；记录请求体与 socket 中断）────
// 初值 normal：gateway boot 时 HealthMonitor 会 probe /v1/messages（sessionId=
// "health-check"），blackhole 会让 probe 超时 → provider 标 DOWN → 真实调用被
// gate。probe 请求（body 含 health-check）恒走 normal。
// socket 中断检测：res.on('close') 在**响应写开始前**就挂上——客户端 abort
// （JDK HttpClient.shutdownNow）→ 连接关闭且 writableEnded=false → 记 abortedAt。
let mode = 'normal';
const requests = []; // {seq, at, body, mode, isProbe, completed, abortedAt}
const sse = (event, data) => `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;

const mock = http.createServer((req, res) => {
  const readBody = () => new Promise((r) => { let b = ''; req.on('data', (c) => (b += c)); req.on('end', () => r(b)); });
  if (req.method === 'POST' && req.url === '/__mode') {
    readBody().then((b) => { mode = b.trim(); res.writeHead(200); res.end(mode); });
    return;
  }
  if (req.method === 'GET' && req.url === '/__log') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ mode, requests: requests.map(({ body, ...rest }) => ({ ...rest, bodyLen: body.length })) }));
    return;
  }
  if (req.method === 'POST' && req.url === '/v1/messages') {
    readBody().then((b) => {
      const isProbe = b.includes('health-check');
      const eff = isProbe ? 'normal' : mode;
      const rec = { seq: requests.length + 1, at: Date.now(), body: b, mode: eff, isProbe, completed: false, abortedAt: null };
      requests.push(rec);
      // abort 检测：连接在响应完成前关闭 = 客户端 abort。
      res.on('close', () => { if (!rec.completed && rec.abortedAt == null && !res.writableEnded) rec.abortedAt = Date.now(); });
      res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' });
      res.write(sse('message_start', { type: 'message_start', message: { usage: { input_tokens: 10 } } }));
      res.write(sse('content_block_start', { index: 0, content_block: { type: 'text' } }));
      res.write(sse('content_block_delta', { index: 0, delta: { type: 'text_delta', text: 'begin' } }));
      if (eff === 'normal') {
        res.write(sse('content_block_delta', { index: 0, delta: { type: 'text_delta', text: ' recovered-ok' } }));
        res.write(sse('content_block_stop', { index: 0 }));
        res.write(sse('message_delta', { delta: { stop_reason: 'end_turn' }, usage: { output_tokens: 5 } }));
        res.write(sse('message_stop', { type: 'message_stop' }));
        rec.completed = true;
        res.end();
      } else {
        // 黑洞：再发 1 chunk 后持有 socket 永不返回——半开连接复刻（T1 形态）。
        res.write(sse('content_block_delta', { index: 0, delta: { type: 'text_delta', text: ' park' } }));
      }
    });
    return;
  }
  res.writeHead(404); res.end();
});

await new Promise((r) => mock.listen(MOCK_PORT, '127.0.0.1', r));
console.log(`mock LLM on 127.0.0.1:${MOCK_PORT} (mode=${mode})`);

// ── WS 客户端（inbox/waiter + marker 模式：断言只认 marker 之后的新帧）────
function wsConnect() {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`${URL_BASE.replace('http', 'ws')}/ws?token=${encodeURIComponent(TOKEN)}`);
    const inbox = [];
    const waiters = [];
    ws.addEventListener('message', (ev) => {
      let msg; try { msg = JSON.parse(ev.data); } catch { return; }
      inbox.push(msg);
      for (let i = waiters.length - 1; i >= 0; i--) {
        const w = waiters[i];
        if (w.from != null && inbox.length - 1 < w.from) continue; // 只认 marker 之后
        if (w.pred(msg)) { waiters.splice(i, 1); w.resolve(msg); }
      }
    });
    ws.addEventListener('open', () => resolve({ ws, inbox, waiters }));
    ws.addEventListener('error', (e) => reject(new Error('WS connect failed: ' + (e.message || 'error'))));
  });
}
function waitForFrom(conn, fromIdx, pred, timeoutMs, label) {
  // 先查 marker 之后的已有帧，再等新帧（都不得早于 fromIdx）。
  const hit = conn.inbox.slice(fromIdx).find(pred);
  if (hit) return Promise.resolve(hit);
  return new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error(`timeout waiting for ${label}`)), Math.min(timeoutMs, remaining()));
    conn.waiters.push({ from: fromIdx, pred, resolve: (m) => { clearTimeout(t); resolve(m); } });
  });
}
const sidOf = (m) => m.sessionId || (m.payload && m.payload.sessionId) || '';

async function createSession(conn, label) {
  const unique = `${label}-${Date.now().toString(36)}`;
  let cursor = conn.inbox.length;
  conn.ws.send(JSON.stringify({ type: 'createSession', name: unique }));
  // 等一个**包含该会话名**的 sessionList（陈旧列表不算）；游标逐帧推进——
  // 连接初始 sessionList 可能晚于 mark 在途到达（空列表），不得反复重查同一帧。
  for (let attempt = 0; attempt < 8; attempt++) {
    const list = await waitForFrom(conn, cursor, (m) => m.type === 'sessionList', 8000, `sessionList(${label})`);
    const idx = conn.inbox.indexOf(list);
    if (idx >= cursor) cursor = idx + 1;
    const mine = (list.sessions || []).find((s) => s.name === unique);
    if (mine) return { sid: mine.id || mine.sessionId, unique };
  }
  const last = conn.inbox.slice(cursor).filter((m) => m.type === 'sessionList').pop()
    || conn.inbox.filter((m) => m.type === 'sessionList').pop();
  throw new Error(`created session '${unique}' never appeared in sessionList; names seen=${JSON.stringify(((last?.sessions) || []).map((s) => s.name))}`);
}

async function wedgeSession(conn, sid, tag) {
  const mark = conn.inbox.length;
  mode = 'blackhole'; // 真实会话请求走黑盒（楔死）；probe 请求恒 normal
  conn.ws.send(JSON.stringify({ type: 'userMessage', sessionId: sid, content: `wedge-${tag}` }));
  await waitForFrom(conn, mark, (m) => m.type === 'textDelta' && sidOf(m) === sid, 20000, `textDelta(${tag})`);
  await sleep(400); // 让 park chunk 落定，lastActivityMs 停走
  return mark;
}

async function waitTurnDone(conn, sid, fromIdx, boundMs, label) {
  await waitForFrom(conn, fromIdx,
    (m) => m.type === 'sessionBusy' && sidOf(m) === sid && m.busy === false, boundMs, `sessionBusy(false) ${label}`);
}

function dumpRequests(from, tag) {
  const rows = requests.slice(from).map((r) => ({
    seq: r.seq, mode: r.mode, probe: r.isProbe, len: r.body.length,
    completed: r.completed, aborted: r.abortedAt != null,
    hasWedge: r.body.includes(`wedge-${tag}`), hasKick: r.body.includes(`kick-${tag}`),
    inj: ['1', '2', '3'].filter((n) => r.body.includes(`inj-${tag}-${n}`)).length,
  }));
  console.log(`  [dump:${tag}] ${JSON.stringify(rows)}`);
}

const conn = await wsConnect();
try {
  // ══ 场景 A：SessionKick 打破楔死 turn（T1/T3）════════════════════════
  if (SCENARIOS.includes('A')) {
    console.log('\n── Scenario A: SessionKick breaks a wedged turn (T1/T3)');
    const { sid } = await createSession(conn, 'hardrec-a');
    const before = requests.length;
    await wedgeSession(conn, sid, 'a');
    const wedged = requests[requests.length - 1];
    check('A1 wedge established (blackhole request dispatched)', wedged && wedged.mode === 'blackhole');

    const markA = conn.inbox.length;
    await sleep(IDLE_WAIT_MS); // idle > kickIdleSec(8s)，< watcher 阈值(12s)
    mode = 'normal';
    conn.ws.send(JSON.stringify({ type: 'userMessage', sessionId: sid, content: 'kick-a' }));
    await waitTurnDone(conn, sid, markA, 45000, 'A recovery');

    const after = requests.slice(before);
    const aborted = after.filter((r) => r.abortedAt != null);
    const recovery = after.find((r) => r.body.includes('kick-a'));
    check('A2 transport abort closed the parked socket (T1)', aborted.length >= 1,
      `${aborted.length} aborted`);
    check('A3 whole-turn redispatch carries BOTH texts (recovery point = full turn)',
      !!recovery && recovery.body.includes('wedge-a') && recovery.body.includes('kick-a'));
    check('A4 session really left Processing (busy=false after kick)', true);
    const tdAfter = conn.inbox.slice(markA).filter((m) => m.type === 'textDelta' && sidOf(m) === sid);
    check('A5 reply delivered after recovery (no silent hang)',
      tdAfter.some((m) => (m.delta || '').includes('recovered-ok')),
      `textDelta-after-kick=${tdAfter.length}`);
    if (failed > 0 && (aborted.length === 0 || !recovery)) dumpRequests(before, 'a');
  }

  // ══ 场景 B：注入合批（T10）══════════════════════════════════════════
  if (SCENARIOS.includes('B')) {
    console.log('\n── Scenario B: queued injections batch into ONE next-round request (T10)');
    const { sid } = await createSession(conn, 'hardrec-b');
    const before = requests.length;
    await wedgeSession(conn, sid, 'b');

    // idle 窗口内连发 3 条注入（不触发 kick——只排队）
    conn.ws.send(JSON.stringify({ type: 'immediateInput', sessionId: sid, content: 'inj-b-1' }));
    conn.ws.send(JSON.stringify({ type: 'immediateInput', sessionId: sid, content: 'inj-b-2' }));
    conn.ws.send(JSON.stringify({ type: 'immediateInput', sessionId: sid, content: 'inj-b-3' }));
    const markB = conn.inbox.length;
    await sleep(IDLE_WAIT_MS); // 现在过 kick 窗口
    mode = 'normal';
    conn.ws.send(JSON.stringify({ type: 'userMessage', sessionId: sid, content: 'kick-b' }));
    await waitTurnDone(conn, sid, markB, 60000, 'B batched recovery');

    const after = requests.slice(before);
    const markers = ['inj-b-1', 'inj-b-2', 'inj-b-3'];
    const carrying = after.map((r) => markers.filter((t) => r.body.includes(t)).length);
    check('B1 ONE request carries ALL 3 injections (batched)', carrying.includes(3),
      `per-request injection counts: [${carrying.join(',')}]`);
    check('B2 no serial per-injection requests (token counterfactual)',
      carrying.every((n) => n === 0 || n === 3), `counts=[${carrying.join(',')}]`);
    check('B3 wedge request itself carried none (queued, not in-flight spliced)',
      carrying.length >= 1 && carrying[0] === 0);
    check('B4 single recovery turn completed (busy=false after kick)', true);
    if (!carrying.includes(3)) dumpRequests(before, 'b');
  }

  // ══ 场景 C：watcher 分级接管（T4）════════════════════════════════════
  if (SCENARIOS.includes('C')) {
    console.log('\n── Scenario C: TaskStuckWatcher graded takeover halt → hard-abort (T4)');
    const { sid } = await createSession(conn, 'hardrec-c');
    const before = requests.length;
    await wedgeSession(conn, sid, 'c');
    console.log(`wedge at ${new Date().toISOString()}, threshold=12s scan=30s — waiting for graded actions`);

    const markC = conn.inbox.length;
    // 第一级：action=halt（attempt-1）
    const haltF = await waitForFrom(conn, markC, (m) => m.type === 'taskStuck' && sidOf(m) === sid && m.action === 'halt', 90000, 'taskStuck(halt)');
    console.log(`  taskStuck action=halt at ${new Date().toISOString()} (idleSecs=${haltF.idleSecs})`);
    // 第二级：action=hard-abort（attempt-2）——halt 对 parked read 无效，升级
    const abortF = await waitForFrom(conn, markC, (m) => m.type === 'taskStuck' && sidOf(m) === sid && m.action === 'hard-abort', 60000, 'taskStuck(hard-abort)');
    const abortFReceivedAt = Date.now(); // 帧无时间戳字段——接收时刻即对齐基准
    console.log(`  taskStuck action=hard-abort at ${new Date().toISOString()} (idleSecs=${abortF.idleSecs})`);
    mode = 'normal'; // abort → RecoverableAbort → 有界重试将打到 normal mock

    await waitTurnDone(conn, sid, markC, 100000, 'C recovery');

    const after = requests.slice(before);
    const abortedRecs = after.filter((r) => r.abortedAt != null);
    check('C1 graded sequence halt → hard-abort observed', true, `${haltF.idleSecs}s → ${abortF.idleSecs}s idle`);
    check('C2 hard-abort (transport) closed the parked socket', abortedRecs.length >= 1,
      `${abortedRecs.length} aborted`);
    const dt = abortedRecs.length ? abortedRecs[abortedRecs.length - 1].abortedAt - Date.parse(abortF.at || new Date().toISOString()) : -1;
    check('C3 socket abort aligned with hard-abort action (not halt)',
      abortedRecs.length >= 1 && Math.abs(dt) < 20000, `Δt=${dt}ms`);
    check('C4 session recovered after graded takeover (busy=false)', true);
    if (abortedRecs.length === 0) dumpRequests(before, 'c');
  }

  console.log(`\n${failed === 0 ? 'ALL PASS' : failed + ' FAILED'}`);
} catch (e) {
  console.error(`FAIL  smoke aborted: ${e.message}`);
  failed++;
} finally {
  conn.ws.close();
  mock.close();
  process.exit(failed === 0 ? 0 : 1);
}
