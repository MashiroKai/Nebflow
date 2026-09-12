#!/usr/bin/env node
// e2e-perm-global.mjs — 权限「全局单一权威源」批（2026-09-12，链 chain-n-931a7e0a）
//
// 设计正本：~/.nebflow/docs/Nebflow/20260912_200602_perm-global-authority-design-r2__chain-n-931a7e0a.md
// 被验语义：**唯一持久权威源 = nebflow.json 的 safety.defaultMode**；会话内切换降级为
// 仅内存的临时覆盖（有效档位 = 覆盖 ?? 全局）；`SessionMeta.safetyMode`（盘上逐会话键）
// 已降级为非权威字段（存量字节零改动 = 方案 A）。
//
// 本脚本覆盖设计 §8 的端到端断言（真隔离实例 + 真 stub LLM + 真权限卡 + 真重启）：
//   A-1/A-3（顺带）  PUT/GET /api/safety/mode|safety —— 写盘 + 权威观测面
//   A-4  热生效：全局 auto-all → confirm-edits 后，**已连接且无覆盖**的会话下一个 Write
//        即出卡（卡帧 safetyMode=confirm-edits），无需重连/重启
//   A-5  覆盖不被全局冲掉：会话内 setSafetyMode=auto-all（覆盖）→ 全局改 confirm-edits
//        → 同会话 Write 无卡；**WS 重连后仍无卡**
//   A-6  重启回落：隔离实例重启后，同会话有效档位 = 全局值（覆盖消失 = 设计意图）
//   A-8  递进链不变：confirm-edits+Write ⇒ 卡带升级项 → allow+upgradeMode=auto-edits ⇒
//        档升 auto-edits（后续 Write 无卡）；auto-edits+危险 Bash ⇒ 卡带升级项 →
//        allow+upgradeMode=auto-all ⇒ 档升 auto-all；**且升级前后该会话在 `_index.json`
//        的 safetyMode 值/存在性不变（不落盘）**
//   A-9  承重：手改某会话 `_index.json` 的 safetyMode=auto-all、全局 confirm-edits ⇒
//        该会话有效档位仍 = confirm-edits（Write 出卡）
//   A-10 R1 失效：制造索引损坏（截断 JSON）触发 recoverOrphans ⇒ 恢复后所有会话的
//        有效档位 = 全局值（且 T-2/R1=C：恢复到盘上的档位 = 权威源当时的值，非硬编码缺省）
//   A-11（隔离实例形态）纯读周期（列表出口）不重写 `_index.json`（hash 不变）
//   A-14 客户端零自动放行（帧面）：meta=auto-all ∧ 全局=confirm-edits ⇒ 后端出卡帧档位 =
//        全局值；会话列表帧（前端 `state.bypassSessions` 的**唯一**语义源）该 sid 的
//        safetyMode = confirm-edits（⇒ 前端不会把它收进 bypassSessions）；
//        **A-14c（修复轮重写）= D1 承重面**：全局=auto-all ∧ 该会话持 confirm-edits
//        覆盖 ⇒ `SessionService` 出口（WS 连接首帧 `sessionList`）必须报**有效档位**；
//        客户端语义复刻器（main.js:1531-1534 + chat.js:2309-2313）由此不得自动放行，
//        且出站帧面无"非受控"`{type:'permissionAnswer', approved:true}`。
//
// 会话身份：探针会话用 agentName='general'（工具面 = Read/Write/Edit/Glob/Grep/Bash）。
// Nebula 的机制工具面不含 Write/Edit/Bash（AgentCore.NebulaOrchestrationTools），测不出写面。
//
// Run（worktree 根，前台）：node scripts/e2e-perm-global.mjs
// Env：GATEWAY_PORT(8097)/MOCK_PORT(28997)/OUT(证据目录)/NEBFLOW_HOME；CLEAN=1 收尾删隔离 home。

import { spawn, execSync } from 'node:child_process';
import http from 'node:http';
import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync, appendFileSync, mkdirSync, existsSync, readdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { tmpdir } from 'node:os';
import { setTimeout as sleep } from 'node:timers/promises';
import { guardFixtureHome, safeRm } from './lib/delguard.mjs';

const REPO = dirname(dirname(fileURLToPath(import.meta.url)));
const GATEWAY_PORT = Number(process.env.GATEWAY_PORT || 8097);
const MOCK_PORT = Number(process.env.MOCK_PORT || 28997);
const BASE = `http://127.0.0.1:${GATEWAY_PORT}`;
const HOME = process.env.NEBFLOW_HOME || join(tmpdir(), `nb-perm-global-${Date.now()}`);
const MAIN_REPO = dirname(execSync('git rev-parse --path-format=absolute --git-common-dir', { cwd: REPO }).toString().trim());
const OUT = process.env.OUT || join(MAIN_REPO, '.nebflow', 'evidence', '20260912_perm-global');
const CONFIG_PATH = join(HOME, 'nebflow.json');
const INDEX_PATH = join(HOME, 'sessions', '_index.json');

guardFixtureHome(HOME, { label: 'e2e-perm-global' });
mkdirSync(OUT, { recursive: true });
const runLogPath = join(OUT, 'run.log');
writeFileSync(runLogPath, '');

const PROBE_DIR = join(HOME, 'probe');
const WRITE_BASE = join(PROBE_DIR, 'write');
const BASH_TARGET = join(tmpdir(), `nb-perm-global-bash-${Date.now()}`);
const CONTENT = 'PERM-GLOBAL-PROBE';

let failed = 0;
const checks = [];
function check(name, ok, extra = '') {
  if (!ok) failed++;
  checks.push({ name, ok, extra });
  log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
}
function log(line) {
  const s = `${new Date().toISOString()} ${line}`;
  console.log(s);
  try { appendFileSync(runLogPath, s + '\n'); } catch { /* best effort */ }
}
async function waitFor(pred, label, timeoutMs = 90000) {
  const t0 = Date.now();
  while (Date.now() - t0 < timeoutMs) {
    const v = await pred();
    if (v) return v;
    await sleep(200);
  }
  throw new Error(`timeout after ${timeoutMs}ms waiting for ${label}`);
}
function sha256(p) { return createHash('sha256').update(readFileSync(p)).digest('hex'); }

// ── 进程清理（trap cleanup EXIT 纪律：只杀自起进程组 + 自起端口占用者）──
const children = [];
let cleaned = false;
let mockServer = null;
function hostListeners8080() {
  try {
    return execSync('lsof -nP -iTCP:8080 -sTCP:LISTEN -t 2>/dev/null', { shell: '/bin/bash' }).toString().trim().split('\n').filter(Boolean).sort().join(' ');
  } catch { return ''; }
}
function killChildren() {
  for (const c of children) {
    try { if (c.pid) process.kill(-c.pid, 'SIGKILL'); } catch { /* already gone */ }
  }
  children.length = 0;
}
async function freePort(port) {
  try {
    const pids = execSync(`lsof -tiTCP:${port} -sTCP:LISTEN 2>/dev/null`, { shell: '/bin/bash' }).toString().trim();
    if (pids) for (const pid of pids.split('\n').filter(Boolean)) {
      try { process.kill(Number(pid), 'SIGKILL'); } catch { /* gone */ }
    }
  } catch { /* no listener — good */ }
}
async function cleanup() {
  if (cleaned) return;
  cleaned = true;
  try { mockServer?.closeAllConnections?.(); } catch { /* node version */ }
  try { mockServer?.close(); } catch { /* already closed */ }
  killChildren();
  await sleep(800);
  await freePort(GATEWAY_PORT);
  await freePort(MOCK_PORT);
  if (process.env.CLEAN === '1' || process.env.NB_CLEAN === '1') safeRm(HOME, { label: 'e2e-perm-global' });
}
for (const sig of ['SIGINT', 'SIGTERM', 'uncaughtException', 'unhandledRejection']) {
  process.on(sig, async (e) => { if (e) console.error(e); await cleanup(); process.exit(1); });
}

// ══ stub LLM（OpenAI 兼容流式）：按轮首 user 文本的 NB_PROBE 标记发对应 tool_call ══
const requests = [];
const toolResults = [];
let stubRound = 0;
let pendingProbe = null;

function msgText(m) {
  const c = m?.content;
  if (typeof c === 'string') return c;
  if (Array.isArray(c)) return c.map((p) => (p && typeof p.text === 'string' ? p.text : '')).join('\n');
  return '';
}
function chunk(delta, finish) {
  return 'data: ' + JSON.stringify({
    id: 'chatcmpl-stub', object: 'chat.completion.chunk', created: Math.floor(Date.now() / 1000), model: 'mock-1',
    choices: [{ index: 0, delta, finish_reason: finish ?? null }],
  }) + '\n\n';
}
function stop() {
  return [chunk({ role: 'assistant', content: '' }), chunk({ content: 'done' }), chunk({}, 'stop'), 'data: [DONE]\n\n'].join('');
}
function toolCall(name, args) {
  return [
    chunk({ role: 'assistant', content: '' }),
    chunk({ tool_calls: [{ index: 0, id: `call_${name}_${stubRound}`, type: 'function', function: { name, arguments: JSON.stringify(args) } }] }),
    chunk({}, 'tool_calls'),
    'data: [DONE]\n\n',
  ].join('');
}
function tailMsg(msgs) {
  for (let i = msgs.length - 1; i >= 0; i--) {
    const m = msgs[i];
    const t = msgText(m).trim();
    if (m.role === 'tool') return m;
    if (!t) continue;
    if (t.startsWith('<system-reminder>')) continue;
    return m;
  }
  return null;
}
function probeSpec(kind, n) {
  switch (kind) {
    case 'WRITE': return { tool: 'Write', args: { file_path: `${WRITE_BASE}-${n}.txt`, content: `${CONTENT}-${n}` } };
    case 'BASH': return { tool: 'Bash', args: { command: `rm -rf ${BASH_TARGET}` } };
    default: return null;
  }
}
function startMock() {
  const server = http.createServer((req, res) => {
    if (req.method === 'POST' && (req.url === '/v1/chat/completions' || req.url === '/chat/completions')) {
      const parts = [];
      req.on('data', (d) => parts.push(d));
      req.on('end', () => {
        const raw = Buffer.concat(parts).toString('utf8');
        let body = {};
        try { body = JSON.parse(raw); } catch { /* tolerate */ }
        stubRound++;
        const msgs = body.messages || [];
        requests.push({ round: stubRound, messages: msgs, ts: Date.now() });
        const tail = tailMsg(msgs);
        let events;
        if (tail && tail.role === 'tool') {
          toolResults.push({ n: toolResults.length + 1, text: msgText(tail).slice(0, 1200) });
          events = stop();
        } else {
          const text = tail ? msgText(tail) : '';
          const m = /NB_PROBE=(WRITE|BASH)#(\d+)/.exec(text);
          if (m) {
            pendingProbe = { kind: m[1], n: Number(m[2]) };
            const spec = probeSpec(pendingProbe.kind, pendingProbe.n);
            log(`[stub] round ${stubRound}: probing ${pendingProbe.kind}#${pendingProbe.n} → ${spec.tool}`);
            events = toolCall(spec.tool, spec.args);
          } else {
            events = stop();
          }
        }
        try {
          res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-cache', connection: 'keep-alive' });
          res.end(events);
        } catch { /* client gone */ }
      });
      return;
    }
    res.writeHead(404).end();
  });
  return new Promise((resolve) => server.listen(MOCK_PORT, '127.0.0.1', () => resolve(server)));
}

// ── fixture home ──
function writeConfig(safetyDefaultMode) {
  const cfg = {
    llm: { providers: { mock: { baseUrl: `http://127.0.0.1:${MOCK_PORT}/v1/`, apiKey: 'sk-e2e-stub', protocol: 'openai', models: [{ id: 'mock-1', maxTokens: 8192, contextWindow: 128000 }] } } },
  };
  if (safetyDefaultMode !== undefined) cfg.safety = { defaultMode: safetyDefaultMode };
  writeFileSync(CONFIG_PATH, JSON.stringify(cfg, null, 2));
}
function buildFixture() {
  mkdirSync(HOME, { recursive: true });
  mkdirSync(PROBE_DIR, { recursive: true });
  writeConfig('auto-all');                  // 起点 = 顶档（A-4 要从顶档热切到低档）
  writeFileSync(join(HOME, 'model-presets.json'), JSON.stringify({
    defaultPreset: 'general',
    presets: { general: { name: 'general', description: '', preferred: 'mock/mock-1', fallbacks: [] } },
  }));
}

// ── 隔离实例（AGENTS.md 隔离配方：--home + --port + NEBFLOW_GATEWAY_PORT env 冗余保险）──
let gatewayLog = '';
function startGateway() {
  const sbt = spawn('sbt', ['-batch', `run --home ${HOME} --port ${GATEWAY_PORT} --no-browser`], {
    cwd: REPO, detached: true, stdio: ['ignore', 'pipe', 'pipe'],
    env: { ...process.env, NEBFLOW_GATEWAY_PORT: String(GATEWAY_PORT), SBT_OPTS: (process.env.SBT_OPTS || '') + ' -Xmx3g' },
  });
  children.push(sbt);
  const pass = (tag) => (d) => {
    const s = String(d);
    gatewayLog += s;
    if (/error|Exception|permission|safety|listen|Started|Gateway|Seed|recover/i.test(s)) console.log(`${tag} ${s.trim().slice(0, 200)}`);
  };
  sbt.stdout.on('data', pass('[sbt]'));
  sbt.stderr.on('data', pass('[sbt!]'));
  return sbt;
}
async function waitGateway(timeoutMs = 300000) {
  const t0 = Date.now();
  while (Date.now() - t0 < timeoutMs) {
    try {
      const r = await fetch(BASE);
      if (r.status < 500) { log(`[poll] gateway answered http ${r.status} after ${Math.round((Date.now() - t0) / 1000)}s`); return true; }
    } catch { /* not yet */ }
    if (Math.round((Date.now() - t0) / 1000) % 15 === 0) log(`[poll] waiting gateway… ${Math.round((Date.now() - t0) / 1000)}s`);
    await sleep(1500);
  }
  return false;
}
/** 重启隔离实例（A-6/A-9/A-10 用）：只动自起进程组与自起端口。 */
async function restartGateway(label) {
  log(`[restart] ${label}: stopping isolated instance`);
  try { ws?.close(); } catch { /* ok */ }
  ws = null;
  killChildren();
  await sleep(1200);
  await freePort(GATEWAY_PORT);
  // 端口必须已空（换端口不在本脚本范围内 —— 停不掉就报错）
  try {
    const still = execSync(`lsof -tiTCP:${GATEWAY_PORT} -sTCP:LISTEN 2>/dev/null`, { shell: '/bin/bash' }).toString().trim();
    if (still) throw new Error(`port ${GATEWAY_PORT} still held by ${still}`);
  } catch (e) { if (String(e.message).includes('still held')) throw e; /* lsof exit 1 = free */ }
  startGateway();
  if (!(await waitGateway(300000))) throw new Error(`${label}: gateway never came back up`);
  await connectWs();
  log(`[restart] ${label}: isolated instance is back up`);
}

// ── 客户端面（REST + WS，全部只打隔离端口）──
let TOKEN = null;
async function api(path, method = 'GET', body) {
  const res = await fetch(`${BASE}/api${path}`, {
    method,
    headers: { authorization: `Bearer ${TOKEN}`, 'content-type': 'application/json' },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text().catch(() => '');
  let json = null; try { json = JSON.parse(text); } catch { /* non-json */ }
  return { status: res.status, json, text };
}
const frames = [];
/** 出站（client→server）帧捕获 —— A-14「客户端零自动放行」的帧面观测点。
 *  `harness: true` = 本脚本**主动**发出的应答（permissionAnswer / setSafetyMode /
 *  createSession）；`harness: false` = 非受控出站帧 = 自动放行形态（永不该出现）。 */
const outbound = [];
let ws = null;
/** harness 主动发送的统一入口（打标记）。 */
function hSend(obj) {
  if (!ws) throw new Error('hSend: no ws');
  ws.__nbHarness = true;
  try { ws.send(JSON.stringify(obj)); } finally { ws.__nbHarness = false; }
}
function connectWs() {
  return new Promise((resolve, reject) => {
    const sock = new WebSocket(`ws://127.0.0.1:${GATEWAY_PORT}/ws?token=${encodeURIComponent(TOKEN)}`);
    sock.addEventListener('open', () => {
      const origSend = sock.send.bind(sock);
      sock.send = (data) => {
        try { outbound.push({ at: Date.now(), harness: !!sock.__nbHarness, json: JSON.parse(data) }); } catch { /* non-json */ }
        return origSend(data);
      };
      ws = sock;
      resolve(sock);
    });
    sock.addEventListener('error', () => reject(new Error('ws connect failed')));
    sock.addEventListener('message', (ev) => {
      try { frames.push({ at: Date.now(), json: JSON.parse(ev.data) }); } catch { /* binary/other */ }
    });
  });
}
/** 客户端语义复刻器（无浏览器会话；逐字复刻前端两处判定）。
 *  · main.js:1531-1534 —— `state.bypassSessions = new Set(allSessions.filter(s => s.safetyMode === 'auto-all').map(s => s.id))`
 *  · chat.js:2309-2313 —— 若目标会话在 `bypassSessions` 内，客户端**立即**发出
 *    `{type:'permissionAnswer', sessionId, approved:true}`（用户零点击）。
 *  返回 {bypass, wouldAutoApprove} —— 即"客户端会不会自动放行这个 sid"。 */
function clientReplica(frame, sid) {
  const all = frame?.json?.sessions || [];
  const bypass = all.filter((s) => s.safetyMode === 'auto-all').map((s) => s.id);
  return { bypass, wouldAutoApprove: bypass.includes(sid) };
}
/** 指定 WS 连接（`fromIdx` 之后）里**首帧** `type === 'sessionList'` 的帧
 *  （= `SessionService.sendSessionList` 出口；`:675` 的每次连接首帧）。 */
function firstConnectListFrame(fromIdx) {
  for (let i = fromIdx; i < frames.length; i++) {
    const j = frames[i].json;
    if (j.type === 'sessionList' && Array.isArray(j.sessions)) return frames[i];
  }
  return null;
}
function newFramesSince(idx, pred) { return frames.slice(idx).filter((f) => pred(f.json)); }
function startTurn(sid, content, timeoutSec = 60) {
  const ac = new AbortController();
  const promise = fetch(`${BASE}/api/sessions/${sid}/turn`, {
    method: 'POST',
    headers: { authorization: `Bearer ${TOKEN}`, 'content-type': 'application/json' },
    body: JSON.stringify({ content, timeoutSec }),
    signal: ac.signal,
  }).then((r) => r.text()).catch((e) => `ABORTED/${e.message}`);
  return { promise, abort: () => ac.abort() };
}
async function sessionsRaw() {
  const r = await api('/sessions');
  return (r.json && r.json.sessions) || [];
}
const norm = (m) => (m === undefined || m === null || m === '<absent>') ? 'confirm-edits' : m;
/** 出口有效档位（GET /api/sessions 的 overlay 值）。 */
async function modeOf(sid) {
  const s = (await sessionsRaw()).find((x) => x.id === sid);
  return s ? norm(s.safetyMode) : '<missing>';
}
/** 盘上值（`_index.json` 原始逐会话键，未 overlay）。索引损坏时返回 {}。 */
function indexRawModes() {
  if (!existsSync(INDEX_PATH)) return {};
  try {
    const j = JSON.parse(readFileSync(INDEX_PATH, 'utf8'));
    const out = {};
    for (const s of j.sessions || []) out[s.id] = s.safetyMode === undefined ? '<absent>' : s.safetyMode;
    return out;
  } catch { return {}; }
}
/** 盘上原始文本里是否还有逐会话 `safetyMode: auto-all`（恢复路径不得写顶档）。 */
function indexHasAutoAll() {
  if (!existsSync(INDEX_PATH)) return false;
  return /"safetyMode"\s*:\s*"auto-all"/.test(readFileSync(INDEX_PATH, 'utf8'));
}
async function createSessionViaWs(name, agentName = 'general') {
  const before = frames.length;
  hSend({ type: 'createSession', name, agentName });
  const isList = (j) => (j.type === 'sessionList' || j.type === 'agentSessionList') && Array.isArray(j.sessions);
  try {
    const f = await waitFor(() => newFramesSince(before, isList).slice(-1)[0],
      `sessionList/agentSessionList after createSession(${name})`, 8000);
    const hit = (f.json.sessions || []).find((s) => s.name === name);
    if (hit) return hit.id;
  } catch (e) { log(`[ws] list-frame lookup failed (${e.message}) — REST fallback`); }
  const found = (await sessionsRaw()).find((s) => s.name === name);
  return found ? found.id : null;
}
/** 最近一条会话列表帧里该 sid 的 safetyMode（前端 bypassSessions 的语义源）。 */
function lastListFrameMode(sid) {
  for (let i = frames.length - 1; i >= 0; i--) {
    const j = frames[i].json;
    if ((j.type === 'sessionList' || j.type === 'agentSessionList') && Array.isArray(j.sessions)) {
      const hit = j.sessions.find((s) => s.id === sid);
      if (hit) return norm(hit.safetyMode);
    }
  }
  return '<no-list-frame>';
}
function dumpEvidence() {
  try {
    writeFileSync(join(OUT, '01-checks.json'), JSON.stringify({ checks, failed }, null, 2));
    writeFileSync(join(OUT, '03-tool-results.json'), JSON.stringify(toolResults, null, 2));
    writeFileSync(join(OUT, '04-askPermission-frames.json'), JSON.stringify(frames.filter((f) => f.json.type === 'askPermission').map((f) => f.json), null, 2));
    writeFileSync(join(OUT, '05-permissionAnswer-frames.json'), JSON.stringify(frames.filter((f) => f.json.type === 'permissionAnswer').map((f) => f.json), null, 2));
    // 出站帧面（client→server）—— A-14c 的真观测面（旧版误查入站集）
    writeFileSync(join(OUT, '09-outbound-frames.json'), JSON.stringify(outbound, null, 2));
    writeFileSync(join(OUT, '06-config.json'), existsSync(CONFIG_PATH) ? readFileSync(CONFIG_PATH, 'utf8') : '(missing)');
    writeFileSync(join(OUT, '07-gateway.log'), gatewayLog);
    if (existsSync(INDEX_PATH)) writeFileSync(join(OUT, '08-index-raw.json'), readFileSync(INDEX_PATH, 'utf8'));
    writeFileSync(join(OUT, '00-summary.txt'),
      `${checks.map((c) => `${c.ok ? 'PASS' : 'FAIL'}  ${c.name}${c.extra ? '  — ' + c.extra : ''}`).join('\n')}\n\n${failed === 0 ? 'ALL PASS' : failed + ' CHECK(S) FAILED'}\n`);
  } catch (e) { log(`[dump] failed: ${e.message}`); }
}

// ══ 主链 ══
const H8080_BEFORE = hostListeners8080();
log(`[guard] 8080 listeners before: ${H8080_BEFORE || '(none)'}`);
log(`[guard] isolated home: ${HOME}`);
log(`[out] evidence dir: ${OUT}`);

/** 一轮 Write 探针：返回 { cardFrames, fileWritten, turnResult }。 */
async function writeProbe(sid, n, cardTimeoutMs = 45000) {
  const file = `${WRITE_BASE}-${n}.txt`;
  const before = frames.length;
  const t = startTurn(sid, `NB_PROBE=WRITE#${n}`);
  let card = null;
  try {
    card = await waitFor(() => newFramesSince(before, (j) => j.type === 'askPermission')[0],
      `askPermission for WRITE#${n}`, cardTimeoutMs);
  } catch { /* 无卡 = 放行路径 */ }
  if (card) {
    // 卡挂起 ⇒ 文件必须还没落盘（确认卡真的拦住了工具）
    const blocked = !existsSync(file);
    hSend({ type: 'permissionAnswer', sessionId: sid, requestId: card.json.requestId, approved: false });
    const result = await t.promise;
    return { card: card.json, blockedWhilePending: blocked, fileWritten: existsSync(file), turnResult: result, sliceStart: before };
  }
  const result = await t.promise;
  return { card: null, blockedWhilePending: null, fileWritten: existsSync(file), turnResult: result, sliceStart: before };
}

try {
  mockServer = await startMock();
  log(`[phase] stub LLM up on :${MOCK_PORT}`);
  buildFixture();
  log(`[phase] fixture built (safety.defaultMode = auto-all)`);
  startGateway();
  if (!(await waitGateway(300000))) throw new Error('gateway never came up');
  check('P0 isolated gateway up (--home/--port 隔离, NEBFLOW_GATEWAY_PORT 主保险)', true, BASE);
  TOKEN = JSON.parse(readFileSync(join(HOME, 'auth.json'), 'utf8'));
  await connectWs();
  check('P0b authenticated WS connected (前端面通路)', true, `${frames.length} frames so far`);

  // ── 观测面（A-3）+ 全局写入口（A-1/A-2） ──
  const s0 = await api('/safety');
  check('A-3 GET /api/safety 返回当前全局档（auto-all）且 configured=true',
    s0.status === 200 && s0.json?.defaultMode === 'auto-all' && s0.json?.configured === true,
    `status=${s0.status} body=${JSON.stringify(s0.json)}`);
  const bad = await api('/safety/mode', 'PUT', { mode: 'yolo' });
  check('A-2 PUT /api/safety/mode yolo ⇒ 400 且文案含合法值列表',
    bad.status === 400 && String(bad.json?.error || '').includes('confirm-edits'),
    `status=${bad.status} error=${bad.json?.error}`);

  // ── 阶段 1（A-4 热生效）：无覆盖会话，全局 auto-all → confirm-edits 即时生效 ──
  const s1 = await createSessionViaWs('perm-global-s1', 'general');
  check('S1 created (no override => effective = global auto-all)', !!s1 && (await modeOf(s1)) === 'auto-all',
    `id=${s1?.slice(0, 8)} mode=${await modeOf(s1)}`);
  const w1 = await writeProbe(s1, 1, 20000);
  check('A-4a 全局 auto-all 下无覆盖会话 Write 无卡且落盘（热切换前的基线）',
    !w1.card && w1.fileWritten, `card=${!!w1.card} file=${w1.fileWritten}`);

  const put1 = await api('/safety/mode', 'PUT', { mode: 'confirm-edits' });
  check('A-1 PUT /api/safety/mode confirm-edits ⇒ 200 + 落盘为 confirm-edits',
    put1.status === 200 &&
      JSON.parse(readFileSync(CONFIG_PATH, 'utf8')).safety?.defaultMode === 'confirm-edits',
    `status=${put1.status} file=${JSON.parse(readFileSync(CONFIG_PATH, 'utf8')).safety?.defaultMode}`);

  const w2 = await writeProbe(s1, 2);
  check('A-4b 全局热切 confirm-edits 后，**同一个已连接**会话的下一个 Write 即出卡（免重连/重启）',
    !!w2.card && w2.card.toolName === 'Write' && w2.card.safetyMode === 'confirm-edits',
    `card=${!!w2.card} safetyMode=${w2.card?.safetyMode}`);
  check('A-4c 卡挂起期间文件未落盘 + deny 后仍未落盘（拦得住，非空转）',
    w2.blockedWhilePending === true && w2.fileWritten === false,
    `blocked=${w2.blockedWhilePending} afterDenyFile=${w2.fileWritten}`);
  check('A-4d 出口有效档位同步为 confirm-edits（未覆盖会话跟随全局）',
    (await modeOf(s1)) === 'confirm-edits', `mode=${await modeOf(s1)}`);

  // ── 阶段 2（A-5 覆盖优先 / 重连保留覆盖） ──
  hSend({ type: 'setSafetyMode', sessionId: s1, safetyMode: 'auto-all' });
  await waitFor(async () => (await modeOf(s1)) === 'auto-all', 'setSafetyMode override → auto-all', 15000);
  check('A-5a 会话内 setSafetyMode=auto-all ⇒ 出口值 = 覆盖值（全局仍是 confirm-edits）',
    (await modeOf(s1)) === 'auto-all' &&
      JSON.parse(readFileSync(CONFIG_PATH, 'utf8')).safety?.defaultMode === 'confirm-edits',
    `mode=${await modeOf(s1)}`);
  const w3 = await writeProbe(s1, 3, 20000);
  check('A-5b 覆盖存在时全局值不冲掉它：Write 无卡且落盘',
    !w3.card && w3.fileWritten, `card=${!!w3.card} file=${w3.fileWritten}`);

  await restartGateway('A-6 重启回落');
  check('A-6 重启后同会话有效档位 = 全局值（覆盖仅内存，进程退出即消失）',
    (await modeOf(s1)) === 'confirm-edits', `mode=${await modeOf(s1)}`);
  check('A-2b 重启后全局值仍为 confirm-edits（PUT 的写盘是持久的）',
    (await api('/safety')).json?.defaultMode === 'confirm-edits');

  // ── 阶段 3（A-8 递进链不变 + 不落盘） ──
  const s2 = await createSessionViaWs('perm-global-s2', 'general');
  const indexBeforeA8 = indexRawModes()[s2] ?? '<no-entry>';
  log(`[A-8] s2=${s2?.slice(0, 8)} 盘上 safetyMode=${indexBeforeA8}`);
  const w4 = await writeProbe(s2, 4);
  check('A-8a confirm-edits + Write ⇒ 卡带当前档位（前端据此渲染升级项）',
    !!w4.card && w4.card.safetyMode === 'confirm-edits' && !w4.fileWritten,
    `safetyMode=${w4.card?.safetyMode} file=${w4.fileWritten}`);

  // allow + upgradeMode=auto-edits（= 用户在卡上点"允许本次并切换到编辑放行"）
  {
    const file = `${WRITE_BASE}-5.txt`;
    const before = frames.length;
    const t = startTurn(s2, 'NB_PROBE=WRITE#5');
    const card = await waitFor(() => newFramesSince(before, (j) => j.type === 'askPermission')[0], 'card A-8b', 60000);
    hSend({ type: 'permissionAnswer', sessionId: s2, requestId: card.json.requestId, approved: true, upgradeMode: 'auto-edits' });
    await t.promise;
    await waitFor(async () => (await modeOf(s2)) === 'auto-edits', 'upgrade → auto-edits', 15000);
    check('A-8b 「允许本次并切编辑放行」⇒ 工具执行 + 档位升到 auto-edits',
      existsSync(file) && (await modeOf(s2)) === 'auto-edits',
      `file=${existsSync(file)} mode=${await modeOf(s2)}`);
  }
  const w6 = await writeProbe(s2, 6, 20000);
  check('A-8c 升级后（auto-edits）Write 不再出卡', !w6.card && w6.fileWritten,
    `card=${!!w6.card} file=${w6.fileWritten}`);

  {
    // auto-edits + 危险 Bash ⇒ 卡带当前档位 → allow + upgradeMode=auto-all
    execSync(`rm -rf ${BASH_TARGET} && mkdir -p ${BASH_TARGET}`, { shell: '/bin/bash' });
    const before = frames.length;
    const t = startTurn(s2, 'NB_PROBE=BASH#7');
    const card = await waitFor(() => newFramesSince(before, (j) => j.type === 'askPermission')[0], 'card A-8d (dangerous Bash)', 60000);
    check('A-8d auto-edits 档下危险 Bash 仍出卡（第二级入口）',
      card.json.toolName === 'Bash' && card.json.safetyMode === 'auto-edits',
      `toolName=${card.json.toolName} safetyMode=${card.json.safetyMode}`);
    check('A-8e 卡挂起期间危险命令未执行（探针目录仍在）', existsSync(BASH_TARGET));
    hSend({ type: 'permissionAnswer', sessionId: s2, requestId: card.json.requestId, approved: true, upgradeMode: 'auto-all' });
    await t.promise;
    await waitFor(async () => (await modeOf(s2)) === 'auto-all', 'upgrade → auto-all', 15000);
    await waitFor(() => !existsSync(BASH_TARGET), 'dangerous bash executed', 15000).catch(() => {});
    check('A-8f 「允许本次并切全部放行」⇒ 危险命令执行 + 档位升到 auto-all',
      !existsSync(BASH_TARGET) && (await modeOf(s2)) === 'auto-all',
      `targetExists=${existsSync(BASH_TARGET)} mode=${await modeOf(s2)}`);
  }
  const indexAfterA8 = indexRawModes()[s2] ?? '<no-entry>';
  check('A-8g 递进升级**不落盘**：升级前后该会话在 `_index.json` 的 safetyMode 值/存在性不变',
    indexAfterA8 === indexBeforeA8,
    `before=${indexBeforeA8} after=${indexAfterA8}`);

  // ── 阶段 4（A-9 + A-14 + A-11）：手改盘上键 = auto-all，全局 = confirm-edits ──
  // 模拟存量会话（盘上带 `safetyMode: "auto-all"`）：直接改 `_index.json`，再重启让
  // 运行中的实例把它读进内存索引（这正是"盘上遗留值"的真实形态）。
  {
    const raw = JSON.parse(readFileSync(INDEX_PATH, 'utf8'));
    let touched = 0;
    for (const s of raw.sessions || []) {
      if (s.id === s2 || s.id === s1) { s.safetyMode = 'auto-all'; touched++; }
    }
    if (touched === 0) throw new Error('A-9 setup: neither s1 nor s2 found in the index');
    writeFileSync(INDEX_PATH, JSON.stringify(raw, null, 2));
    log(`[A-9] hand-edited ${touched} session(s) to safetyMode=auto-all in the persisted index`);
  }
  await restartGateway('阶段4（读入手改的盘上值）');

  // A-9（承重）：盘上 auto-all 不构成权威 ⇒ 出口有效档位 = 全局值（confirm-edits）
  const s2Exit = await modeOf(s2);
  check('A-9 承重：盘上 safetyMode=auto-all + 全局 confirm-edits ⇒ 出口有效档位 = confirm-edits',
    s2Exit === 'confirm-edits', `exit=${s2Exit} disk=${indexRawModes()[s2]}`);
  check('A-9b 对照：盘上键本身仍是 auto-all（= 方案 A「读时忽略」，不是把数据改了）',
    indexRawModes()[s2] === 'auto-all', `disk=${indexRawModes()[s2]}`);

  // A-14 承重（帧面）：前端 `state.bypassSessions` 的语义源 = 会话列表帧的逐会话
  // safetyMode；该值必须 = 有效档位（confirm-edits）⇒ 前端不会把该会话当"顶档"静默放行。
  try {
    await waitFor(() => lastListFrameMode(s2) !== '<no-list-frame>', 'initial session list frame after reconnect', 20000);
  } catch { /* 下面用断言直接判 */ }
  const listMode = lastListFrameMode(s2);
  check('A-14a 会话列表帧（bypassSessions 语义源）该 sid 的 safetyMode = 有效档位 confirm-edits',
    listMode === 'confirm-edits', `listFrameMode=${listMode}`);

  const w8 = await writeProbe(s2, 8);
  check('A-14b 盘上 auto-all + 全局 confirm-edits ⇒ 该会话 Write **出卡**，卡帧档位 = 全局值',
    !!w8.card && w8.card.safetyMode === 'confirm-edits' && w8.fileWritten === false,
    `card=${!!w8.card} safetyMode=${w8.card?.safetyMode} file=${w8.fileWritten}`);
  // ── A-14c（2026-09-12 修复轮·证据面缺陷 1 重写）──────────────────────────────
  // 旧版判据恒真空转：它在 `frames`（**入站** server→client 帧集）里过滤
  // `type === 'permissionAnswer'`——而 `permissionAnswer` 是 client→server 帧，
  // 永不进入入站集 ⇒ 判据是 `0 === 0`，任何实现/任何变异下都恒绿。
  //
  // 新版判据（能真红）把两个面都纳入观测：
  //   ① **客户端语义复刻器**（`clientReplica`，逐字复刻 main.js:1531-1534 +
  //      chat.js:2309-2313）：把**本次 WS 连接的首帧 `sessionList`**（=
  //      `SessionService.sendSessionList` 出口 —— D1 唯一漏注入面）喂进复刻器，
  //      得到 `state.bypassSessions` 与"客户端会不会静默发出 approved:true"。
  //   ② **出站帧面**：连接建立后 patch `sock.send` 捕获全部 client→server 载荷，
  //      断言不存在"非 harness 主动应答"的 `permissionAnswer`（= 自动放行形态）。
  //   ③ **D1 承重场景**（复刻器必须能分辨漏注入）：全局切回 `auto-all`，而目标会话
  //      持有 **`confirm-edits` 覆盖**（会话内收紧）⇒ 有效档位 = 覆盖 = confirm-edits。
  //      未修 D1 时该出口报**全局值 auto-all** ⇒ 复刻器把该 sid 收进 bypassSessions
  //      ⇒ `wouldAutoApprove = true` ⇒ 本检查**红**；修后首帧 = 有效档位 ⇒ 绿。
  //      （判据的自证：`frameMode === effective`，其中 effective 取 REST 出口基准。）
  {
    await api('/safety/mode', 'PUT', { mode: 'auto-all' });
    hSend({ type: 'setSafetyMode', sessionId: s2, safetyMode: 'confirm-edits' });
    await waitFor(async () => (await modeOf(s2)) === 'confirm-edits', 's2 override → confirm-edits', 15000);
    const outboundBefore = outbound.length;
    const framesBeforeReconnect = frames.length;
    try { ws.close(); } catch { /* ok */ }
    await sleep(800);
    await connectWs();
    await waitFor(() => firstConnectListFrame(framesBeforeReconnect) !== null,
      'connect-time sessionList frame (SessionService.sendSessionList 出口)', 20000);
    const connectFrame = firstConnectListFrame(framesBeforeReconnect);
    const frameMode = norm((connectFrame.json.sessions.find((s) => s.id === s2) || {}).safetyMode);
    const effective = await modeOf(s2);             // REST 出口 = 有效档位（修后的基准面）
    const replica = clientReplica(connectFrame, s2);
    // 复刻器发射（证据）：若复刻判定"客户端会自动放行"，就把客户端真正会发的那一帧
    // 发出去并留痕（标记 clientSim，不计入 unsolicited 统计）——变异形态下它就是
    // `{type:'permissionAnswer', sessionId, approved:true}`（无 requestId、零点击）。
    let simEmitted = false;
    if (replica.wouldAutoApprove) {
      ws.__nbHarness = true;
      try { ws.send(JSON.stringify({ type: 'permissionAnswer', sessionId: s2, approved: true })); }
      finally { ws.__nbHarness = false; }
      outbound[outbound.length - 1].clientSim = true;
      simEmitted = true;
    }
    const unsolicited = outbound.slice(outboundBefore).filter((o) => !o.harness && o.json?.type === 'permissionAnswer');
    check('A-14c 客户端零自动放行（D1 承重）：连接首帧 = **有效档位**（覆盖优先，非全局值）⇒ 复刻器不把该 sid 收进 bypassSessions，且出站面无自动放行帧',
      effective === 'confirm-edits' && frameMode === effective && !replica.wouldAutoApprove && unsolicited.length === 0,
      `connectFrameMode=${frameMode} effective(REST)=${effective} frameSessions=${(connectFrame.json.sessions || []).length} bypassFromFrame=${replica.bypass.length} wouldAutoApprove=${replica.wouldAutoApprove} clientSimEmitted=${simEmitted} unsolicitedAnswers=${unsolicited.length}`);
    // 复原全局档（后续 A-11 / 阶段 5 的基线是 confirm-edits）
    await api('/safety/mode', 'PUT', { mode: 'confirm-edits' });
    await waitFor(async () => (await api('/safety')).json?.defaultMode === 'confirm-edits', 'global restored to confirm-edits', 15000);
  }

  // A-11（隔离实例形态）：纯读周期（权威观测面 + 会话列表出口）不重写 `_index.json`
  const hashBefore = sha256(INDEX_PATH);
  await api('/safety');
  await api('/sessions');
  await sleep(1500);
  const hashAfter = sha256(INDEX_PATH);
  check('A-11 纯读周期（GET /api/safety + GET /api/sessions）不重写 `_index.json`（hash 不变）',
    hashBefore === hashAfter, `${hashBefore.slice(0, 12)} → ${hashAfter.slice(0, 12)}`);

  // ── 阶段 5（A-10 R1 失效）：索引损坏 ⇒ recoverOrphans ⇒ 恢复后有效档位 = 全局值 ──
  {
    const raw = readFileSync(INDEX_PATH, 'utf8');
    writeFileSync(INDEX_PATH, raw.slice(0, Math.max(20, Math.floor(raw.length * 0.4)))); // 截断（半截 JSON）
    log(`[A-10] index truncated to 40% (${raw.length} → ${Math.floor(raw.length * 0.4)} bytes) to force recoverOrphans`);
  }
  await restartGateway('阶段5（索引损坏恢复）');
  await sleep(1500); // 让恢复 + 首启的索引重建落定

  const allSessions = await sessionsRaw();
  const exits = allSessions.map((s) => `${s.id.slice(0, 8)}:${norm(s.safetyMode)}`);
  check('A-10a 索引损坏恢复后，所有会话的**有效档位** = 全局值（confirm-edits）',
    allSessions.length > 0 && allSessions.every((s) => norm(s.safetyMode) === 'confirm-edits'),
    `sessions=${allSessions.length} exits=${exits.join(',')}`);
  check('A-10b 恢复出的会话 ≥ 2（确实走了 recoverOrphans 重建，而不是空索引）',
    allSessions.length >= 2, `sessions=${allSessions.length}`);

  // 触发一次索引回写（任何 createSession 都会 saveIndex），把恢复出的 meta 落到盘上，
  // 再直接读 `_index.json` 断言 T-2/R1=C（恢复路径写的是权威值，不是硬编码缺省）。
  // 判据用 **归一化** 后的盘上值：`confirm-edits` 在盘上被 Encoder 表示为「省略键」
  // （SessionMeta.scala:38-40），而 `auto-all` 会被**显式写出**——所以这条断言是
  // 可分辨的：修前（构造缺省 auto-all）盘上会出现 `"safetyMode": "auto-all"`。
  const recoveredIds = allSessions.map((s) => s.id);
  await createSessionViaWs('perm-global-post-recovery', 'general');
  const disk = indexRawModes();
  const recoveredDisk = recoveredIds.map((id) => `${id.slice(0, 8)}:${norm(disk[id])}`);
  check('T-2/R1=C 钉：恢复路径写回盘上的档位（归一化）= 权威源当时的值 confirm-edits，**不是**硬编码缺省 auto-all',
    !indexHasAutoAll() && recoveredDisk.every((x) => x.endsWith(':confirm-edits')),
    `diskAutoAll=${indexHasAutoAll()} recovered=${recoveredDisk.join(',')} rawKeys=${JSON.stringify(disk)}`);
} catch (e) {
  log(`FATAL ${e && e.stack ? e.stack : e}`);
  check('run completed without fatal error', false, String((e && e.message) || e));
} finally {
  const H8080_AFTER = hostListeners8080();
  check('Z1 宿主 8080 监听集合与启动前完全一致', H8080_BEFORE === H8080_AFTER, `${H8080_BEFORE || '(none)'} → ${H8080_AFTER || '(none)'}`);
  dumpEvidence();
  await cleanup();
  log(failed === 0 ? 'ALL PASS' : `${failed} CHECK(S) FAILED`);
  process.exit(failed === 0 ? 0 : 1);
}
