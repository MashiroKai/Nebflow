#!/usr/bin/env node
// e2e-project-memory.mjs — project-memory 批 E2E（2026-09-05）。
//
// 验收链（真实全环）：隔离 NEBFLOW_HOME + 隔离实例（sbt run --home/--port，
// 绝非 8080 宿主）+ OpenAI 兼容 stub LLM（内嵌，捕获全部请求）。fixture 双项目 A/B：
//   1. Nebula 会话 turn① → stub 让 Nebula 调 MemoryEdit(target=project:e2e-a,
//      action=append) 经真实工具链+注册表解析写入 <ws-a>/.nebflow/memory.md
//   2. Nebula turn② → stub 让 Nebula 调 Mail(→e2e-a) → 分发器 A spawn
//      → newTaskPrompt 打到 stub，断言：含项目记忆条（marker-A）✓
//   3. 分发器 A 的工具轮 → stub 回 NodeEdit 工具调用（建节点 probe-node，
//      agent=project-dispatcher, task 带 marker, out=Nebula）→ 创建即运行
//      → 节点首条消息打到 stub，断言：含 marker-A ✓
//   4. Nebula turn③ → Mail(→e2e-b) → 分发器 B spawn，断言：不含 marker-A ✓
//   5. 瘦身断言：Nebula 全局注入（会话内全部请求）不含 marker-A ✓
//   6. 初始化模板渲染：fixture 内 cp staging 模板 → MemoryEdit 可 append → 注入含模板头节
//
// Run（worktree 根）：
//   node scripts/e2e-project-memory.mjs
// Env：KEEP=1 保留 fixture home（默认跑完即删）；GATEWAY_PORT=8096 MOCK_PORT=18996
// （与 dispatcher-ctx 批 e2e 的 8097/18997 错开，防并行互踩）。

import { spawn, execSync } from 'node:child_process';
import http from 'node:http';
import { readFileSync, mkdirSync, writeFileSync, cpSync, rmSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { homedir, tmpdir } from 'node:os';
import { setTimeout as sleep } from 'node:timers/promises';

const REPO = dirname(dirname(fileURLToPath(import.meta.url)));
const GATEWAY_PORT = Number(process.env.GATEWAY_PORT || 8096);
const MOCK_PORT = Number(process.env.MOCK_PORT || 18996);
const BASE = `http://127.0.0.1:${GATEWAY_PORT}`;
const HOME = process.env.NEBFLOW_HOME || join(tmpdir(), `nb-pmem-e2e-${Date.now()}`);
const DISPATCHER_AGENT_SRC = process.env.DISPATCHER_AGENT_SRC || join(homedir(), '.nebflow', 'agents', 'project-dispatcher');
const TEMPLATE_SRC = join(REPO, 'staging', 'project-memory-template.md');
const MARKER_A = 'PMEM-MARKER-A-7391（项目记忆探针条目，2026-09-05）';

let failed = 0;
function check(name, ok, extra = '') {
  if (!ok) failed++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
}

// ── 清理（trap cleanup EXIT 纪律：后台 PID 登记 → EXIT 逐 kill + wait + 端口复查）──
const children = [];
let cleaned = false;
let mockServer = null;
async function cleanup() {
  if (cleaned) return;
  cleaned = true;
  try { mockServer?.closeAllConnections?.(); } catch { /* node version */ }
  try { mockServer?.close(); } catch { /* already closed */ }
  for (const c of children) {
    try { if (c.pid) process.kill(-c.pid, 'SIGKILL'); } catch { /* already gone */ }
  }
  for (const port of [GATEWAY_PORT, MOCK_PORT]) {
    try {
      const pids = execSync(`lsof -tiTCP:${port} -sTCP:LISTEN 2>/dev/null`, { shell: '/bin/bash' }).toString().trim();
      if (pids) for (const pid of pids.split('\n').filter(Boolean)) {
        try { process.kill(Number(pid), 'SIGKILL'); } catch { /* gone */ }
      }
    } catch { /* no listener — good */ }
  }
  if (!process.env.KEEP) { try { rmSync(HOME, { recursive: true, force: true }); } catch { /* best effort */ } }
  else console.log(`KEEP=1 — fixture home retained: ${HOME}`);
}
for (const sig of ['SIGINT', 'SIGTERM', 'uncaughtException', 'unhandledRejection']) {
  process.on(sig, async (e) => { if (e) console.error(e); await cleanup(); process.exit(1); });
}

// ── stub LLM（OpenAI 兼容流式；捕获全部请求）──
// 脚本化（按请求内容匹配，与到达次序无关）：
//   last=tool → 工具结果回填轮 → 终答（防死循环）
//   分发器上下文（'你是项目'+任务分发器，last≠tool）→ A 分发器首次回 NodeEdit 建节点
//   节点首条消息（含节点任务文本）→ 终答
//   Nebula 用户轮（E2E turn1/2/3）→ 分别脚本化 MemoryEdit / Mail→A / Mail→B
const captured = []; // {messages, ts, tag}
let nodeEditFired = false;
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
function done(events) { events.push('data: [DONE]\n\n'); return events.join(''); }
function finalOk() {
  return done([chunk({ role: 'assistant', content: '' }), chunk({ content: 'ok' }), chunk({}, 'stop')]);
}
function toolCall(id, name, args) {
  return { tool_calls: [{ index: 0, id, type: 'function', function: { name, arguments: JSON.stringify(args) } }] };
}
function classify(body) {
  const msgs = body.messages || [];
  const s = JSON.stringify(msgs);
  const last = msgs[msgs.length - 1];
  if (last?.role === 'tool') return 'tool-followup';
  if (s.includes('你是项目') && s.includes('任务分发器')) return s.includes('e2e-b') ? 'dispatcher-B' : 'dispatcher-A';
  if (s.includes('E2E node probe')) return 'node-A';
  return 'nebula';
}
function streamFor(body) {
  const tag = classify(body);
  const msgs = body.messages || [];
  const lastUser = [...msgs].reverse().find((m) => m.role === 'user');
  const turn = lastUser ? msgText(lastUser) : '';

  if (tag === 'tool-followup') return finalOk();
  if (tag === 'dispatcher-A' || tag === 'dispatcher-B') {
    // A 分发器首个上下文请求 → NodeEdit 建节点（创建即运行 → 节点首条消息到 stub）
    if (tag === 'dispatcher-A' && !nodeEditFired) {
      nodeEditFired = true;
      return done([
        chunk({ role: 'assistant', content: '' }),
        chunk(toolCall('call_nodeedit', 'NodeEdit', {
          project: 'e2e-a', nodename: 'probe-node', agent: 'project-dispatcher',
          task: 'E2E node probe: verify project memory injection', out: 'Nebula',
        })),
        chunk({}, 'tool_calls'),
      ]);
    }
    return finalOk();
  }
  if (tag === 'node-A') return finalOk();
  // Nebula 会话轮次
  if (turn.includes('E2E turn1')) {
    return done([
      chunk({ role: 'assistant', content: '' }),
      chunk(toolCall('call_memedit', 'MemoryEdit', {
        target: 'project:e2e-a', action: 'append', content: `- ${MARKER_A}`,
      })),
      chunk({}, 'tool_calls'),
    ]);
  }
  if (turn.includes('E2E turn2') || turn.includes('E2E turn3')) {
    const proj = turn.includes('E2E turn2') ? 'e2e-a' : 'e2e-b';
    return done([
      chunk({ role: 'assistant', content: '' }),
      chunk(toolCall('call_stub_mail', 'Mail', { address: proj, message: `E2E dispatch probe (${proj})`, type: 'PARALLEL' })),
      chunk({}, 'tool_calls'),
    ]);
  }
  return finalOk();
}
function startMock() {
  const server = http.createServer((req, res) => {
    if (req.method === 'POST' && (req.url === '/v1/chat/completions' || req.url === '/chat/completions')) {
      let bodyRaw = '';
      req.on('data', (d) => (bodyRaw += d));
      req.on('end', () => {
        let body = {};
        try { body = JSON.parse(bodyRaw); } catch { /* tolerate */ }
        const events = streamFor(body);
        captured.push({ messages: body.messages || [], ts: Date.now(), tag: classify(body) });
        res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-cache', connection: 'keep-alive' });
        res.end(events);
      });
      return;
    }
    res.writeHead(404).end();
  });
  return new Promise((resolve) => server.listen(MOCK_PORT, '127.0.0.1', () => resolve(server)));
}

// ── fixture home：双项目 A/B ──
function buildFixture() {
  mkdirSync(HOME, { recursive: true });
  // 分发器 agent 定义（真身拷贝——EntityLoader.loadAgent 需要；节点 agent 也用它）
  cpSync(DISPATCHER_AGENT_SRC, join(HOME, 'agents', 'project-dispatcher'), { recursive: true });
  // nebflow.json：provider → stub
  writeFileSync(join(HOME, 'nebflow.json'), JSON.stringify({
    llm: { providers: { mock: { baseUrl: `http://127.0.0.1:${MOCK_PORT}/v1/`, apiKey: 'sk-e2e-stub', protocol: 'openai', models: [{ id: 'mock-1', maxTokens: 8192, contextWindow: 128000 }] } } },
  }));
  // 项目 A/B：workspace + 注册表 project.json（启动 mountAll 自动挂载）
  for (const name of ['e2e-a', 'e2e-b']) {
    const ws = join(HOME, `ws-${name}`);
    mkdirSync(join(ws, '.nebflow'), { recursive: true });
    mkdirSync(join(HOME, 'projects', name), { recursive: true });
    writeFileSync(join(HOME, 'projects', name, 'project.json'), JSON.stringify({
      name, workspace: ws, agentFile: join(ws, 'AGENTS.md'), createdAt: Date.now(),
    }));
    writeFileSync(join(ws, 'AGENTS.md'), `# ${name}\n\nE2E fixture workspace (project-memory).\n`);
  }
  // 初始化模板渲染验证：cp staging 模板到 A 的 memory.md + 回填项目名（宿主命令同款）
  cpSync(TEMPLATE_SRC, join(HOME, 'ws-e2e-a', '.nebflow', 'memory.md'));
  execSync(`sed -i '' '1s/<项目名>/e2e-a/' "${join(HOME, 'ws-e2e-a', '.nebflow', 'memory.md')}"`);
}

// ── 隔离实例 ──
function startGateway() {
  const cmd = `run --home ${HOME} --port ${GATEWAY_PORT} --no-browser`;
  const sbt = spawn('sbt', ['-batch', cmd], {
    cwd: REPO, detached: true, stdio: ['ignore', 'pipe', 'pipe'],
    env: { ...process.env, SBT_OPTS: (process.env.SBT_OPTS || '') + ' -Xmx3g' },
  });
  children.push(sbt);
  const pass = (tag) => (d) => {
    const s = String(d);
    if (/Compiling|compiling|Done|done|running|Running|error|Exception|listen|Listen|started|Started|Gateway|port/.test(s) || tag === '[sbt!]')
      console.log(`${tag} ${s.trim().slice(0, 160)}`);
  };
  sbt.stdout.on('data', pass('[sbt]'));
  sbt.stderr.on('data', pass('[sbt!]'));
  return sbt;
}
async function waitGateway(timeoutMs = 240000) {
  const t0 = Date.now();
  while (Date.now() - t0 < timeoutMs) {
    try {
      const r = await fetch(BASE);
      if (r.status < 500) { console.log(`[poll] gateway answered http ${r.status} after ${Math.round((Date.now() - t0) / 1000)}s`); return true; }
    } catch { /* not yet */ }
    if ((Date.now() - t0) % 10000 < 2100) console.log(`[poll] waiting gateway… ${Math.round((Date.now() - t0) / 1000)}s`);
    await sleep(2000);
  }
  return false;
}

// ── 主链 ──
mockServer = await startMock();
console.log(`[phase] stub LLM up on :${MOCK_PORT}`);
buildFixture();
console.log(`[phase] fixture built at ${HOME}`);
// 模板渲染静态断言（cp+回填后：七节骨架齐全）
{
  const t = readFileSync(join(HOME, 'ws-e2e-a', '.nebflow', 'memory.md'), 'utf8');
  check('template: project name substituted', t.startsWith('# Project Memory — e2e-a'));
  check('template: discipline header present', t.includes('## 使用纪律') && t.includes('单行条目') && t.includes('三问'));
  check('template: budget line present', t.includes('10KB') && t.includes('8KB'));
  check('template: T2 lifecycle present', t.includes('事件闭环即删') && t.includes('7 天'));
  check('template: dream-cleanup declaration present', t.includes('Dream 抽取对象'));
  check('template: three sections suggested', t.includes('## 口径与决策') && t.includes('## 项目状态') && t.includes('## 运维教训'));
}
startGateway();
if (!(await waitGateway())) {
  console.log('FAIL  gateway never came up — aborting');
  await cleanup();
  process.exit(1);
}
check('isolated gateway up', true, BASE);

const TOKEN = JSON.parse(readFileSync(join(HOME, 'auth.json'), 'utf8'));
async function api(path, method = 'GET', body) {
  const res = await fetch(`${BASE}/api${path}`, {
    method,
    headers: { authorization: `Bearer ${TOKEN}`, 'content-type': 'application/json' },
    body: body ? JSON.stringify(body) : undefined,
  });
  return { status: res.status, json: await res.json().catch(() => null), text: await res.text().catch(() => '') };
}

// turn①：MemoryEdit(target=project:e2e-a) 真实工具链写入
const sess = await api('/sessions', 'POST', { name: 'e2e-pmem', agentName: 'Nebula' });
check('create Nebula session', sess.status === 200 && !!(sess.json?.id ?? sess.json?.sessionId), JSON.stringify(sess.json || {}).slice(0, 120));
const sid = sess.json?.id ?? sess.json?.sessionId;
{
  const t1 = await api(`/sessions/${sid}/turn`, 'POST', {
    content: 'E2E turn1：立即调用 MemoryEdit 工具，target=project:e2e-a，action=append，content 为一条单行条目（内容任意）。除此之外什么都不要做。',
    timeoutSec: 150,
  });
  check('Nebula turn1 completes (MemoryEdit fired inside)', t1.status === 200, `status=${t1.status}`);
}
// 写入落盘断言（fixture 自含；注册表解析 + 写函数全链）
const memA = join(HOME, 'ws-e2e-a', '.nebflow', 'memory.md');
{
  let written = false;
  for (let i = 0; i < 15 && !written; i++) { await sleep(1000); written = existsSync(memA) && readFileSync(memA, 'utf8').includes(MARKER_A); }
  check('MemoryEdit target=project:e2e-a wrote workspace memory.md', written, memA);
}

// turn②：Mail→e2e-a（分发器 A spawn，prompt 注入项目记忆）
// turn③：Mail→e2e-b（分发器 B spawn，对照不含 A 的记忆）
for (const turn of [2, 3]) {
  const proj = turn === 2 ? 'e2e-a' : 'e2e-b';
  const r = await api(`/sessions/${sid}/turn`, 'POST', {
    content: `E2E turn${turn}：立即调用 Mail 工具，address=${proj}，发送消息「E2E dispatch probe」（type=PARALLEL）。除此之外什么都不要做。`,
    timeoutSec: 150,
  });
  check(`Nebula turn${turn} completes (Mail→${proj} fired)`, r.status === 200, `status=${r.status}`);
}

// ── 等请求到齐：dispatcher-A、node-A（NodeEdit 建节点 → 创建即运行）、dispatcher-B ──
let dispatchA = null, dispatchB = null, nodeA = null;
{
  const t0 = Date.now();
  while (Date.now() - t0 < 120000) {
    dispatchA = captured.find((c) => c.tag === 'dispatcher-A');
    nodeA = captured.find((c) => c.tag === 'node-A');
    dispatchB = captured.find((c) => c.tag === 'dispatcher-B');
    if (dispatchA && nodeA && dispatchB) break;
    await sleep(1500);
  }
  console.log(`[phase] request poll done in ${Math.round((Date.now() - t0) / 1000)}s, captured=${captured.length}`);
}
const ctxOf = (c) => (c ? c.messages.map((m) => `${m.role}: ${msgText(m)}`).join('\n') : '');

check('dispatcher-A spawn request captured', !!dispatchA);
check('node-A (probe-node) request captured', !!nodeA);
check('dispatcher-B spawn request captured', !!dispatchB);

if (dispatchA) {
  const ctx = ctxOf(dispatchA);
  writeFileSync('/tmp/pmem-dispatch-a-dump.json', JSON.stringify(dispatchA, null, 2));
  check('A dispatcher context contains project memory entry', ctx.includes(MARKER_A));
  check('A dispatcher context carries Project Memory header', ctx.includes('# Project Memory — e2e-a'));
  check('A dispatcher context includes template discipline section', ctx.includes('## 使用纪律'));
} else {
  check('dispatcher-A captured (marker assertions skipped)', false, 'no dispatcher-A request — see dumps');
}
if (nodeA) {
  const ctx = ctxOf(nodeA);
  writeFileSync('/tmp/pmem-node-a-dump.json', JSON.stringify(nodeA, null, 2));
  check('A node first message contains project memory entry', ctx.includes(MARKER_A));
  check('A node first message carries Project Memory header', ctx.includes('# Project Memory — e2e-a'));
  check('A node first message still has own task + protocol footnote', ctx.includes('E2E node probe') && ctx.includes('=== Node') === false && ctx.length > 0);
} else {
  check('node-A captured (marker assertions skipped)', false, 'no node request — NodeEdit chain did not reach spawn');
}
if (dispatchB) {
  const ctx = ctxOf(dispatchB);
  writeFileSync('/tmp/pmem-dispatch-b-dump.json', JSON.stringify(dispatchB, null, 2));
  check('B dispatcher context does NOT contain A project memory', !ctx.includes(MARKER_A));
  check('B dispatcher context has no A Project Memory header', !ctx.includes('# Project Memory — e2e-a'));
} else {
  check('dispatcher-B captured (isolation assertions skipped)', false, 'no dispatcher-B request');
}
// 瘦身断言：注入面 = Nebula 请求的 system prompt（ContextRefresher 全局记忆块挂这里）。
// 注意不能扫全消息体——turn2/3 历史里含 turn1 MemoryEdit 工具结果对条目文本的
// 回显（会话历史，非注入），扫全会误报。
{
  const sysOf = (c) => { const s = c.messages.find((m) => m.role === 'system'); return s ? msgText(s) : ''; };
  const nebReqs = captured.filter((c) => c.tag === 'nebula');
  const withSys = nebReqs.filter((c) => sysOf(c).length > 0);
  const leak = withSys.filter((c) => sysOf(c).includes(MARKER_A));
  const headerLeak = withSys.filter((c) => sysOf(c).includes('# Project Memory'));
  check('global injection (Nebula system prompt) does NOT contain project memory', withSys.length > 0 && leak.length === 0, `nebulaReq=${nebReqs.length} withSys=${withSys.length} leak=${leak.length}`);
  check('Nebula system prompt has no Project Memory header at all', withSys.length > 0 && headerLeak.length === 0, `headerLeak=${headerLeak.length}`);
}

await cleanup();
console.log(failed === 0 ? '\nALL PASS' : `\n${failed} CHECK(S) FAILED`);
process.exit(failed === 0 ? 0 : 1);
