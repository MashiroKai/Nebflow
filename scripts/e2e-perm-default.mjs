#!/usr/bin/env node
// e2e-perm-default.mjs — 权限放行模式「启动默认 = 全部放行」批（2026-09-12，链 chain-n-28816752）
//
// 被验需求（作者 2026-09-12 13:46 裁定）：「把 nebflow 启动时的信任模式默认开全部放行」
//   —— 权限放行模式（每次询问 / 编辑放行 / 全部放行）启动后默认即最顶档；
//      会话内仍可切回低档（顶档只是初始值，不是焊死）；模式机与确认卡语义不动。
//
// 本脚本的验收链（全真实：真隔离实例 + 真 stub LLM 工具调用 + 真权限卡 + 真落盘）：
//   ① 初始态：fresh home（**无** safety.defaultMode 键）下，三处会话来源的初始档必须 = 全部放行
//      A2 启动会话（createDefaultSession/migrateFromLegacy 路径）/ A3 REST 新建 / A4 WS 新建，
//      并实测顶档下 Write 不再出卡且真的落盘（A5）。
//   ② 回退通路：WS setSafetyMode → confirm-edits 落盘生效，Write **确实又被拦**（出卡 + 未落盘 + deny 后仍未落盘）。
//   ④ 递进放行链：confirm-edits 卡 → allow + upgradeMode=auto-edits（编辑放行）；
//      编辑放行档下危险 Bash 仍出卡（safetyMode=auto-edits）→ allow + upgradeMode=auto-all（全部放行）；
//      顶档下危险 Bash 不再出卡且真的执行。
//   ③ 硬保护在顶档下零削弱（运行期两例）：交互式命令硬拒（BashTool）+ 工具白名单总闸（H1，
//      general 面调 Mail 被丢弃）——顶档下**不出卡且仍然拒/仍然丢**；其余硬保护逐条见批报告（代码锚）。
//   E. 默认值边界：safety.defaultMode 热读（免重启）→ confirm-edits / auto-edits 新建即随配置；
//      键移除 ⇒ 回落到启动默认（顶档）；既有会话档位不被改写（零回溯）。
//
// 会话身份说明：探针会话用 agentName='general'（收敛通用执行 agent，工具面 = BaseTools 六件
// Read/Write/Edit/Glob/Grep/Bash）。Nebula 的机制固定工具面**不含** Write/Edit/Bash
// （AgentCore.NebulaOrchestrationTools，2026-09-05 23:34 作者裁定）——用 Nebula 会话测不出
// 写/执行面。权限档位是**会话级**（bucket key = rootSessionId），与 agent 身份无关。
//
// Run（worktree 根，前台）：
//   node scripts/e2e-perm-default.mjs
// Env：GATEWAY_PORT(8096)/MOCK_PORT(28996)/OUT(证据目录)/NEBFLOW_HOME；CLEAN=1 收尾删隔离 home（默认保留）。

import { spawn, execSync } from 'node:child_process';
import http from 'node:http';
import { readFileSync, writeFileSync, appendFileSync, mkdirSync, existsSync, rmSync, readdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { tmpdir } from 'node:os';
import { setTimeout as sleep } from 'node:timers/promises';
import { guardFixtureHome, safeRm } from './lib/delguard.mjs';

const REPO = dirname(dirname(fileURLToPath(import.meta.url)));
const GATEWAY_PORT = Number(process.env.GATEWAY_PORT || 8096);
const MOCK_PORT = Number(process.env.MOCK_PORT || 28996);
const BASE = `http://127.0.0.1:${GATEWAY_PORT}`;
const HOME = process.env.NEBFLOW_HOME || join(tmpdir(), `nb-perm-default-${Date.now()}`);
const MAIN_REPO = dirname(execSync('git rev-parse --path-format=absolute --git-common-dir', { cwd: REPO }).toString().trim());
const OUT = process.env.OUT || join(MAIN_REPO, '.nebflow', 'evidence', '20260912_perm-default');
const CONFIG_PATH = join(HOME, 'nebflow.json');

guardFixtureHome(HOME, { label: 'e2e-perm-default' });
mkdirSync(OUT, { recursive: true });
const runLogPath = join(OUT, 'run.log');
writeFileSync(runLogPath, '');

// 探针路径（全在隔离 home / 隔离 tmp 内，绝不碰宿主）
const PROBE_DIR = join(HOME, 'probe');
const WRITE_BASE = join(PROBE_DIR, 'write');
const BASH_TARGET = join(tmpdir(), `nb-perm-bash-probe-${Date.now()}`);
const CONTENT = 'PERM-DEFAULT-PROBE';

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

// ── 进程清理（trap cleanup EXIT 纪律：只杀自起进程组 + 自起端口占用者）──
const children = [];
let cleaned = false;
let mockServer = null;
function hostListeners8080() {
  try {
    return execSync('lsof -nP -iTCP:8080 -sTCP:LISTEN -t 2>/dev/null', { shell: '/bin/bash' }).toString().trim().split('\n').filter(Boolean).sort().join(' ');
  } catch { return ''; }
}
async function cleanup() {
  if (cleaned) return;
  cleaned = true;
  try { mockServer?.closeAllConnections?.(); } catch { /* node version */ }
  try { mockServer?.close(); } catch { /* already closed */ }
  for (const c of children) {
    try { if (c.pid) process.kill(-c.pid, 'SIGKILL'); } catch { /* already gone */ }
  }
  await sleep(800);
  for (const port of [GATEWAY_PORT, MOCK_PORT]) {
    try {
      const pids = execSync(`lsof -tiTCP:${port} -sTCP:LISTEN 2>/dev/null`, { shell: '/bin/bash' }).toString().trim();
      if (pids) for (const pid of pids.split('\n').filter(Boolean)) {
        try { process.kill(Number(pid), 'SIGKILL'); } catch { /* gone */ }
      }
    } catch { /* no listener — good */ }
  }
  if (process.env.CLEAN === '1' || process.env.NB_CLEAN === '1') safeRm(HOME, { label: 'e2e-perm-default' });
}
for (const sig of ['SIGINT', 'SIGTERM', 'uncaughtException', 'unhandledRejection']) {
  process.on(sig, async (e) => { if (e) console.error(e); await cleanup(); process.exit(1); });
}

// ══ stub LLM（OpenAI 兼容流式）：按轮首 user 文本的 NB_PROBE 标记发对应 tool_call ══
// 轮次语义：turn 第 1 次请求（尾部 = 用户标记）→ 发工具调用；工具结果回来的续轮 → 收尾文本。
const requests = [];       // 全部请求（含 messages）
const toolResults = [];    // { n, name, text } —— 从续轮的 tool 消息里抽取的真实工具结果
let stubRound = 0;
let pendingProbe = null;   // { kind, n }

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
function stop(body) {
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
/** 尾部判定：跳过 system-reminder 后第一条非空消息。tool 消息 ⇒ 这是工具续轮。 */
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
    case 'INT': return { tool: 'Bash', args: { command: 'vim /tmp/never-opened.txt' } };
    // H1 工具白名单（真执行面总闸）：Mail 只配 Nebula/team，general 面**没有**它 ⇒
    // 应被丢弃成 "Tool not available"（在权限判定之前），与放行模式无关。
    case 'TOOL': return { tool: 'Mail', args: { address: 'x', message: 'y' } };
    default: return null;
  }
}
function startMock() {
  const server = http.createServer((req, res) => {
    if (req.method === 'POST' && (req.url === '/v1/chat/completions' || req.url === '/chat/completions')) {
      const parts = [];
      req.on('data', (d) => parts.push(d));
      req.on('end', async () => {
        const raw = Buffer.concat(parts).toString('utf8');   // 一次性解码（多字节 UTF-8 跨 chunk）
        let body = {};
        try { body = JSON.parse(raw); } catch { /* tolerate */ }
        stubRound++;
        const msgs = body.messages || [];
        requests.push({ round: stubRound, messages: msgs, ts: Date.now() });
        const tail = tailMsg(msgs);
        let events;
        if (tail && tail.role === 'tool') {
          toolResults.push({ n: toolResults.length + 1, text: msgText(tail).slice(0, 1200) });
          events = stop(body);
        } else {
          const text = tail ? msgText(tail) : '';
          const m = /NB_PROBE=(WRITE|BASH|INT|TOOL)#(\d+)/.exec(text);
          if (m) {
            pendingProbe = { kind: m[1], n: Number(m[2]) };
            const spec = probeSpec(pendingProbe.kind, pendingProbe.n);
            log(`[stub] round ${stubRound}: probing ${pendingProbe.kind}#${pendingProbe.n} → ${spec.tool}`);
            events = toolCall(spec.tool, spec.args);
          } else {
            events = stop(body);
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

// ── fixture home（fresh：**不含** safety.defaultMode 键 —— 验「未配置 = 启动默认」）──
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
  writeConfig(undefined);                    // 阶段 A–D：无 safety 键
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
    if (/error|Exception|permission|safety|listen|Started|Gateway|Seed/i.test(s)) console.log(`${tag} ${s.trim().slice(0, 160)}`);
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
let ws = null;
function connectWs() {
  return new Promise((resolve, reject) => {
    const sock = new WebSocket(`ws://127.0.0.1:${GATEWAY_PORT}/ws?token=${encodeURIComponent(TOKEN)}`);
    sock.addEventListener('open', () => { ws = sock; resolve(sock); });
    sock.addEventListener('error', () => reject(new Error('ws connect failed')));
    sock.addEventListener('message', (ev) => {
      try { frames.push({ at: Date.now(), json: JSON.parse(ev.data) }); } catch { /* binary/other */ }
    });
  });
}
function newFramesSince(idx, pred) { return frames.slice(idx).filter((f) => pred(f.json)); }
/** 发一轮 headless turn（后台跑），返回 {promise, abort}。 */
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
/** `<absent>` ≡ confirm-edits：SessionMeta 的 Encoder 在 ==confirm-edits 时**省略该键**
  * （SessionMeta.scala:34-36），Decoder 兜底回 confirm-edits（:66）。读数归一化。 */
const norm = (m) => (m === undefined || m === null || m === '<absent>') ? 'confirm-edits' : m;
async function modeOf(sid) {
  const s = (await sessionsRaw()).find((x) => x.id === sid);
  return s ? norm(s.safetyMode) : '<missing>';
}
async function indexModes() {
  const p = join(HOME, 'sessions', '_index.json');
  if (!existsSync(p)) return {};
  const j = JSON.parse(readFileSync(p, 'utf8'));
  const out = {};
  for (const s of j.sessions || []) out[s.id] = norm(s.safetyMode);
  return out;
}
async function createSessionViaWs(name, agentName = 'general') {
  const before = frames.length;
  ws.send(JSON.stringify({ type: 'createSession', name, agentName }));
  // 回帧形态：带 agentName 时 WS 处理器回 `agentSessionList`（by-name 列表），
  // 不带则回 `sessionList`（统一列表）——两种都收，匹配不到再落 REST 兜底。
  const isList = (j) => (j.type === 'sessionList' || j.type === 'agentSessionList') && Array.isArray(j.sessions);
  try {
    const f = await waitFor(() => newFramesSince(before, isList).slice(-1)[0],
      `sessionList/agentSessionList after createSession(${name})`, 8000);
    const hit = (f.json.sessions || []).find((s) => s.name === name);
    if (hit) return hit.id;
  } catch (e) { log(`[ws] list-frame lookup failed (${e.message}) — REST fallback`); }
  const found = (await sessionsRaw()).find((s) => s.name === name);   // REST fallback
  return found ? found.id : null;
}

function dumpEvidence(extra = {}) {
  try {
    writeFileSync(join(OUT, '01-checks.json'), JSON.stringify({ checks, failed }, null, 2));
    writeFileSync(join(OUT, '02-requests.json'), JSON.stringify(
      requests.map((r) => ({ round: r.round, lastRole: tailMsg(r.messages)?.role ?? null, lastText: (tailMsg(r.messages) ? msgText(tailMsg(r.messages)) : '').slice(0, 400) })), null, 2));
    writeFileSync(join(OUT, '03-tool-results.json'), JSON.stringify(toolResults, null, 2));
    writeFileSync(join(OUT, '04-askPermission-frames.json'), JSON.stringify(frames.filter((f) => f.json.type === 'askPermission').map((f) => f.json), null, 2));
    writeFileSync(join(OUT, '05-frame-types.json'), JSON.stringify(
      frames.reduce((acc, f) => { const t = String(f.json.type); acc[t] = (acc[t] || 0) + 1; return acc; }, {}), null, 2));
    writeFileSync(join(OUT, '06-config.json'), existsSync(CONFIG_PATH) ? readFileSync(CONFIG_PATH, 'utf8') : '(missing)');
    writeFileSync(join(OUT, '07-gateway.log'), gatewayLog);
    writeFileSync(join(OUT, '00-summary.txt'),
      `${checks.map((c) => `${c.ok ? 'PASS' : 'FAIL'}  ${c.name}${c.extra ? '  — ' + c.extra : ''}`).join('\n')}\n\n${failed === 0 ? 'ALL PASS' : failed + ' CHECK(S) FAILED'}\n`);
  } catch (e) { log(`[dump] failed: ${e.message}`); }
}

// ══ 主链 ══
const H8080_BEFORE = hostListeners8080();
log(`[guard] 8080 listeners before: ${H8080_BEFORE || '(none)'}`);
log(`[guard] isolated home: ${HOME}`);
log(`[out] evidence dir: ${OUT}`);

const modesSnapshot = {};   // 会话 id → 观测到的档位（逐阶段留痕）

try {
  mockServer = await startMock();
  log(`[phase] stub LLM up on :${MOCK_PORT}`);
  buildFixture();
  log(`[phase] fixture built (no safety.defaultMode key)`);
  startGateway();
  if (!(await waitGateway(300000))) throw new Error('gateway never came up');
  check('A1 isolated gateway up (fresh home, --home/--port 隔离)', true, BASE);
  TOKEN = JSON.parse(readFileSync(join(HOME, 'auth.json'), 'utf8'));
  await connectWs();
  check('A1b authenticated WS connected (frontend 面通路)', true, `${frames.length} frames so far`);

  // ── 阶段 A：初始态 = 全部放行（三个会话来源） ──
  const bootSessions = (await sessionsRaw()).filter((s) => (s.agentName || '') === 'Nebula');
  const boot = bootSessions.sort((a, b) => a.createdAt - b.createdAt)[0];
  check('A2 启动会话（createDefaultSession/migrateFromLegacy 路径）= auto-all',
    !!boot && norm(boot.safetyMode) === 'auto-all',
    `id=${boot?.id?.slice(0, 8)} name=${boot?.name} safetyMode=${boot?.safetyMode ?? '<absent>'}`);

  const rest = await api('/sessions', 'POST', { name: 'perm-rest-created', agentName: 'Nebula' });
  const restId = rest.json?.id;
  check('A3 REST 新建会话 = auto-all', rest.status === 200 && norm(rest.json?.safetyMode) === 'auto-all',
    `status=${rest.status} safetyMode=${rest.json?.safetyMode ?? '<absent>'}`);

  const wsCreatedId = await createSessionViaWs('perm-ws-created', 'general');
  check('A4 WS 新建会话（前端 modal 通路）= auto-all',
    !!wsCreatedId && (await modeOf(wsCreatedId)) === 'auto-all',
    `id=${wsCreatedId?.slice(0, 8)} safetyMode=${await modeOf(wsCreatedId)}`);

  // A5：顶档实测 —— Write 工具调用不出卡且真的落盘
  const a5File = `${WRITE_BASE}-5.txt`;
  let t = startTurn(wsCreatedId, 'NB_PROBE=WRITE#5');
  let r = await t.promise;
  check('A5a 顶档 Write 轮完成（无权限卡 → 工具直接执行）', r !== 'ABORTED/undefined' && existsSync(a5File),
    `turn=${String(r).slice(0, 60)} file=${existsSync(a5File)}`);
  check('A5b 顶档 Write 轮零 askPermission 帧', frames.filter((f) => f.json.type === 'askPermission').length === 0,
    `askPermission frames=${frames.filter((f) => f.json.type === 'askPermission').length}`);

  // ── 阶段 B：会话内回退到 confirm-edits（②） ──
  const sid = wsCreatedId;
  ws.send(JSON.stringify({ type: 'setSafetyMode', sessionId: sid, safetyMode: 'confirm-edits' }));
  await waitFor(async () => (await modeOf(sid)) === 'confirm-edits', 'setSafetyMode → confirm-edits persisted', 15000);
  check('B1 会话内切回 confirm-edits 已落盘生效', (await modeOf(sid)) === 'confirm-edits', `mode=${await modeOf(sid)}`);
  modesSnapshot.B_confirmEdits = await indexModes();

  const bFile = `${WRITE_BASE}-6.txt`;
  const framesBeforeB = frames.length;
  t = startTurn(sid, 'NB_PROBE=WRITE#6');
  const card = await waitFor(() => newFramesSince(framesBeforeB, (j) => j.type === 'askPermission')[0], 'askPermission (confirm-edits + Write)', 60000);
  check('B2a confirm-edits 下 Write 出确认卡（切回低档确实又被拦）',
    card.json.toolName === 'Write' && card.json.safetyMode === 'confirm-edits',
    `toolName=${card.json.toolName} safetyMode=${card.json.safetyMode} dangerLevel=${card.json.dangerLevel}`);
  check('B2b 卡挂起期间文件未落盘（工具确实被拦住）', !existsSync(bFile), `file=${existsSync(bFile)}`);
  ws.send(JSON.stringify({ type: 'permissionAnswer', sessionId: sid, requestId: card.json.requestId, approved: false }));
  r = await t.promise;
  check('B3 deny 后仍未落盘（拒绝生效，非空转）', !existsSync(bFile), `turn=${String(r).slice(0, 50)} file=${existsSync(bFile)}`);

  // ── 阶段 C：递进放行链（④） ──
  const c1File = `${WRITE_BASE}-7.txt`;
  const framesBeforeC1 = frames.length;
  t = startTurn(sid, 'NB_PROBE=WRITE#7');
  const cardC1 = await waitFor(() => newFramesSince(framesBeforeC1, (j) => j.type === 'askPermission')[0], 'card C1', 60000);
  check('C1a 编辑确认卡携带当前档位（前端据此渲染升级按钮）', cardC1.json.safetyMode === 'confirm-edits', `safetyMode=${cardC1.json.safetyMode}`);
  ws.send(JSON.stringify({ type: 'permissionAnswer', sessionId: sid, requestId: cardC1.json.requestId, approved: true, upgradeMode: 'auto-edits' }));
  r = await t.promise;
  await waitFor(async () => (await modeOf(sid)) === 'auto-edits', 'upgrade → auto-edits', 15000);
  check('C1b 「允许本次并切编辑放行」：工具执行 + 档位升到 auto-edits',
    existsSync(c1File) && (await modeOf(sid)) === 'auto-edits',
    `file=${existsSync(c1File)} mode=${await modeOf(sid)}`);

  const c2File = `${WRITE_BASE}-8.txt`;
  const framesBeforeC2 = frames.length;
  t = startTurn(sid, 'NB_PROBE=WRITE#8');
  r = await t.promise;
  check('C2 编辑放行档下 Write 不再出卡（递进链第一级生效）',
    existsSync(c2File) && newFramesSince(framesBeforeC2, (j) => j.type === 'askPermission').length === 0,
    `file=${existsSync(c2File)} newCards=${newFramesSince(framesBeforeC2, (j) => j.type === 'askPermission').length}`);

  rmSync(BASH_TARGET, { recursive: true, force: true }); mkdirSync(BASH_TARGET, { recursive: true });
  const framesBeforeC3 = frames.length;
  t = startTurn(sid, 'NB_PROBE=BASH#9');
  const cardC3 = await waitFor(() => newFramesSince(framesBeforeC3, (j) => j.type === 'askPermission')[0], 'card C3 (dangerous Bash)', 60000);
  check('C3a 编辑放行档下危险 Bash 仍出卡（第二级入口）',
    cardC3.json.toolName === 'Bash' && cardC3.json.safetyMode === 'auto-edits',
    `toolName=${cardC3.json.toolName} safetyMode=${cardC3.json.safetyMode} dangerLevel=${cardC3.json.dangerLevel}`);
  check('C3b 卡挂起期间危险命令未执行（探针目录仍在）', existsSync(BASH_TARGET), `target exists=${existsSync(BASH_TARGET)}`);
  ws.send(JSON.stringify({ type: 'permissionAnswer', sessionId: sid, requestId: cardC3.json.requestId, approved: true, upgradeMode: 'auto-all' }));
  r = await t.promise;
  await waitFor(async () => (await modeOf(sid)) === 'auto-all', 'upgrade → auto-all', 15000);
  await waitFor(() => !existsSync(BASH_TARGET), 'dangerous bash executed', 15000).catch(() => {});
  check('C3c 「允许本次并切全部放行」：危险命令执行 + 档位升到 auto-all',
    !existsSync(BASH_TARGET) && (await modeOf(sid)) === 'auto-all',
    `target exists=${existsSync(BASH_TARGET)} mode=${await modeOf(sid)}`);

  rmSync(BASH_TARGET, { recursive: true, force: true }); mkdirSync(BASH_TARGET, { recursive: true });
  const framesBeforeC4 = frames.length;
  t = startTurn(sid, 'NB_PROBE=BASH#10');
  r = await t.promise;
  await waitFor(() => !existsSync(BASH_TARGET), 'dangerous bash executed (top tier)', 15000).catch(() => {});
  check('C4 顶档下危险 Bash 不再出卡且直接执行（= 「改造后不再拦截的动作」实测条目）',
    !existsSync(BASH_TARGET) && newFramesSince(framesBeforeC4, (j) => j.type === 'askPermission').length === 0,
    `target exists=${existsSync(BASH_TARGET)} newCards=${newFramesSince(framesBeforeC4, (j) => j.type === 'askPermission').length}`);

  // ── 阶段 D：硬保护在顶档下零削弱（运行期两例） ──
  const framesBeforeD1 = frames.length;
  const resultsBeforeD1 = toolResults.length;
  t = startTurn(sid, 'NB_PROBE=INT#11');
  r = await t.promise;
  const d1res = toolResults.length > resultsBeforeD1 ? toolResults[toolResults.length - 1].text : '';
  check('D1 交互式命令硬拒未被顶档削弱（无卡 + 仍拒）',
    newFramesSince(framesBeforeD1, (j) => j.type === 'askPermission').length === 0 && d1res.includes('Interactive command'),
    `newCards=${newFramesSince(framesBeforeD1, (j) => j.type === 'askPermission').length} result="${d1res.slice(0, 120)}"`);

  const framesBeforeD2 = frames.length;
  const resultsBeforeD2 = toolResults.length;
  t = startTurn(sid, 'NB_PROBE=TOOL#12');
  r = await t.promise;
  const d2res = toolResults.length > resultsBeforeD2 ? toolResults[toolResults.length - 1].text : '';
  check('D2 工具白名单总闸（H1）未被顶档削弱：general 面调 Mail → 无卡 + 仍被丢弃',
    newFramesSince(framesBeforeD2, (j) => j.type === 'askPermission').length === 0 && d2res.includes('Tool not available: Mail'),
    `newCards=${newFramesSince(framesBeforeD2, (j) => j.type === 'askPermission').length} result="${d2res.slice(0, 120)}"`);

  // ── 阶段 E：默认值边界（配置优先 / 热读免重启 / 键移除回落 / 零回溯） ──
  const modesBeforeE = await indexModes();
  writeConfig('confirm-edits');                                   // 热读：不重启
  const e1Id = await createSessionViaWs('perm-cfg-confirm');
  check('E1 config safety.defaultMode=confirm-edits（热读，免重启）⇒ 新建会话 = confirm-edits',
    (await modeOf(e1Id)) === 'confirm-edits', `mode=${await modeOf(e1Id)}`);
  writeConfig('auto-edits');
  const e2Id = await createSessionViaWs('perm-cfg-autoedits');
  check('E2 safety.defaultMode=auto-edits ⇒ 新建会话 = auto-edits',
    (await modeOf(e2Id)) === 'auto-edits', `mode=${await modeOf(e2Id)}`);
  writeConfig(undefined);                                          // 键移除
  const e3Id = await createSessionViaWs('perm-cfg-unset');
  check('E3 键移除 ⇒ 新建会话回落到启动默认 = auto-all',
    (await modeOf(e3Id)) === 'auto-all', `mode=${await modeOf(e3Id)}`);
  const modesAfterE = await indexModes();
  const unchanged = Object.entries(modesBeforeE).every(([id, m]) => modesAfterE[id] === m);
  check('E4 既有会话档位零回溯（配置改写不翻历史；本会话仍 auto-all）',
    unchanged && (await modeOf(sid)) === 'auto-all' && modesAfterE[sid] === 'auto-all',
    `sessions checked=${Object.keys(modesBeforeE).length} unchanged=${unchanged} sid=${modesAfterE[sid]}`);
  modesSnapshot.E_final = modesAfterE;
} catch (e) {
  log(`FATAL ${e && e.stack ? e.stack : e}`);
  check('run completed without fatal error', false, String((e && e.message) || e));
} finally {
  const H8080_AFTER = hostListeners8080();
  check('Z1 宿主 8080 监听集合与启动前完全一致', H8080_BEFORE === H8080_AFTER, `${H8080_BEFORE || '(none)'} → ${H8080_AFTER || '(none)'}`);
  dumpEvidence({ indexModes: modesSnapshot.E_final || {} });
  try {
    const sessDir = join(HOME, 'sessions');
    const idx = join(sessDir, '_index.json');
    if (existsSync(idx)) writeFileSync(join(OUT, '08-index-raw.json'), readFileSync(idx, 'utf8'));
    writeFileSync(join(OUT, '09-home-listing.txt'),
      `${existsSync(sessDir) ? readdirSync(sessDir).join('\n') : '(no sessions dir)'}\n\nBASH_TARGET exists=${existsSync(BASH_TARGET)}\n`);
  } catch (e) { log(`[dump2] failed: ${e.message}`); }
  await cleanup();
  dumpEvidence({});
  log(failed === 0 ? 'ALL PASS' : `${failed} CHECK(S) FAILED`);
  process.exit(failed === 0 ? 0 : 1);
}
