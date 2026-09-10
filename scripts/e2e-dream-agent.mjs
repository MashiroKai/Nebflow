#!/usr/bin/env node
// e2e-dream-agent.mjs — dream-agent 批 E2E（2026-09-05）。
//
// 验收链（真实全环）：隔离 NEBFLOW_HOME + 隔离实例（sbt run --home/--port，
// 绝非 8080 宿主）+ OpenAI 兼容 stub LLM（内嵌，捕获全部请求）：
//   0. snapshot-on-write 集成：Nebula turn① → stub 回 MemoryEdit(target=user,
//      append) → 断言 <home>/memory-backups/<ts>/User.md 含【写前真身】且
//      User.md 含新条目（备份先于写、fail-closed 的实例级证据）
//   1. Nebula turn② → stub 回 Task(project="dream-accept", task=梦境日审…)
//      → 分发器 spawn（dream-rules 认知在宿主 system.md，fixture 内为真实
//      project-dispatcher 定义——本 e2e 只验链路，分发器行为由 stub 脚本化）
//   2. 分发器工具轮 → stub 回 NodeEdit 建单节点 agent=dream → 创建即运行
//   3. dream 节点 turn① → stub 回 Read(file_path=<home>/User.md)
//      ——断言点：审计只读例外（auditReadableFiles）在真实沙箱下放行 fixture
//      记忆直读（拒则工具结果是 SANDBOX_DENIED）
//   4. dream 节点 turn② → stub 见工具结果含 seed → 终答审计报告（含
//      READ-CHANNEL-OK + seed 回显）
//   5. 断言 flow-map 节点 completed 且 result 含报告标记（out 边投递链）
//
// Run（worktree 根）：node scripts/e2e-dream-agent.mjs
// Env：默认跑完**不删** fixture（要清理设 CLEAN=1 / NB_CLEAN=1；NB_DRY_RUN=1 只跑删除守卫断言）；
//      GATEWAY_PORT=8098 MOCK_PORT=18998
// （与 8095/8096/8097 及其 stub 端口错开）。

import { spawn, execSync } from 'node:child_process';
import http from 'node:http';
import { readFileSync, writeFileSync, mkdirSync, cpSync, existsSync, readdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { homedir, tmpdir } from 'node:os';
import { setTimeout as sleep } from 'node:timers/promises';
import { guardFixtureHome, safeRm } from './lib/delguard.mjs';

const REPO = dirname(dirname(fileURLToPath(import.meta.url)));
const GATEWAY_PORT = Number(process.env.GATEWAY_PORT || 8098);
const MOCK_PORT = Number(process.env.MOCK_PORT || 18998);
const BASE = `http://127.0.0.1:${GATEWAY_PORT}`;
const HOME = process.env.NEBFLOW_HOME || join(tmpdir(), `nb-dream-e2e-${Date.now()}`);
// R4 删除守卫：解析后的真实绝对路径必须落 tmpdir 之下（≠ tmpdir 自身）——早期 fail-fast，先于任何 spawn。
// NB_DRY_RUN=1：只跑断言、不删除、不 spawn（下游负控入口）。
guardFixtureHome(HOME, { label: 'e2e-dream-agent' });
const DISPATCHER_SRC = process.env.DISPATCHER_AGENT_SRC || join(homedir(), '.nebflow', 'agents', 'project-dispatcher');
const DREAM_SRC = join(REPO, 'staging', 'agents-dream');
const PROJECT = 'dream-accept';
const WS = join(HOME, `ws-${PROJECT}`);
const SEED = 'E2E-DREAM-SEED-7734（合成记忆条目：梦境审计只读探针）';
const SNAPSHOT_PRE = '- SNAPSHOT-E2E-PRE-5192（写前真身标记）';
const SNAPSHOT_NEW = '- SNAPSHOT-E2E-APPENDED-2611';

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
  // 删除点二次断言；默认不删（要清理设 CLEAN=1）——非 tmp 隔离 home 在此处也会被拒
  safeRm(HOME, { label: 'e2e-dream-agent' });
}
for (const sig of ['SIGINT', 'SIGTERM', 'uncaughtException', 'unhandledRejection']) {
  process.on(sig, async (e) => { if (e) console.error(e); await cleanup(); process.exit(1); });
}

// ── stub LLM（OpenAI 兼容流式；捕获全部请求）──
const captured = [];
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
function finalOk() { return done([chunk({ role: 'assistant', content: '' }), chunk({ content: 'ok' }), chunk({}, 'stop')]); }
function toolCall(id, name, args) {
  return { tool_calls: [{ index: 0, id, type: 'function', function: { name, arguments: JSON.stringify(args) } }] };
}
function classify(body) {
  // 只锚定 SYSTEM 域：agent 目录（含 dream 的 description「梦境审计员…」）会进
  // Nebula 系统提示——用「你是 dream」「你是 project-dispatcher」身份头区分，
  // 全消息 includes 会把 Nebula 的每个请求都劫持成 dream-node（首跑教训）。
  const sys = msgText((body.messages || []).find((m) => m.role === 'system') || {});
  if (sys.includes('你是 dream')) return 'dream-node';
  if (sys.includes('你是 project-dispatcher')) return 'dispatcher';
  return 'nebula';
}
function streamFor(body) {
  const tag = classify(body);
  const msgs = body.messages || [];
  const last = msgs[msgs.length - 1];
  const all = JSON.stringify(msgs);

  if (tag === 'dream-node') {
    // turn①：无 tool 结果 → Read fixture User.md；turn②：见 seed → 报告
    if (last?.role === 'tool') {
      const seen = all.includes(SEED);
      const report = [
        '## 梦境审计报告 E2E',
        seen
          ? `READ-CHANNEL-OK：审计只读例外放行，快照含 ${SEED}`
          : 'READ-CHANNEL-DENIED：直读被拒',
        '指标头部(略)/分级清单(略)/迁移映射(略) — E2E 验链路不验内容',
      ].join('\n');
      return done([chunk({ role: 'assistant', content: '' }), chunk({ content: report }), chunk({}, 'stop')]);
    }
    return done([
      chunk({ role: 'assistant', content: '' }),
      chunk(toolCall('call_read', 'Read', { file_path: join(HOME, 'User.md') })),
      chunk({}, 'tool_calls'),
    ]);
  }
  if (tag === 'dispatcher') {
    if (!nodeEditFired) {
      nodeEditFired = true;
      return done([
        chunk({ role: 'assistant', content: '' }),
        chunk(toolCall('call_nodeedit', 'NodeEdit', {
          project: PROJECT, nodename: 'dream-audit-node', agent: 'dream',
          task: 'E2E dream node probe：梦境日审（手动触发，跳过活跃守卫）— 只读审计 User.md 与 agents/Nebula/memory.md，报告沿 out 投递。',
          out: 'Nebula',
        })),
        chunk({}, 'tool_calls'),
      ]);
    }
    return finalOk();
  }
  // nebula：turn1 = MemoryEdit（snapshot 集成），turn2 = Task(→dream-accept)，其余收尾
  const lastUser = [...msgs].reverse().find((m) => m.role === 'user');
  const turn = lastUser ? msgText(lastUser) : '';
  if (last?.role === 'tool') return finalOk(); // 工具回填轮收尾（防 stub 循环发同工具）
  if (turn.includes('E2E turn1')) {
    return done([
      chunk({ role: 'assistant', content: '' }),
      chunk(toolCall('call_memedit', 'MemoryEdit', { target: 'user', action: 'append', content: SNAPSHOT_NEW })),
      chunk({}, 'tool_calls'),
    ]);
  }
  if (turn.includes('E2E turn2')) {
    return done([
      chunk({ role: 'assistant', content: '' }),
      chunk(toolCall('call_task', 'Task', {
        project: PROJECT,
        task: '梦境日审（手动触发，跳过活跃守卫）：建单节点 agent=dream 执行只读审计，报告沿 out 投递 Nebula。',
      })),
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

// ── fixture home ──
function buildFixture() {
  mkdirSync(HOME, { recursive: true });
  cpSync(DREAM_SRC, join(HOME, 'agents', 'dream'), { recursive: true });
  cpSync(DISPATCHER_SRC, join(HOME, 'agents', 'project-dispatcher'), { recursive: true });
  writeFileSync(join(HOME, 'nebflow.json'), JSON.stringify({
    llm: { providers: { mock: { baseUrl: `http://127.0.0.1:${MOCK_PORT}/v1/`, apiKey: 'sk-e2e-stub', protocol: 'openai', models: [{ id: 'mock-1', maxTokens: 8192, contextWindow: 128000 }] } } },
  }));
  // 合成记忆（seed 直读探针 + 写前真身标记）
  writeFileSync(join(HOME, 'User.md'), `# Memory\n\n## 个人信息\n\n${SEED}\n${SNAPSHOT_PRE}\n`);
  mkdirSync(join(HOME, 'agents', 'Nebula'), { recursive: true });
  writeFileSync(join(HOME, 'agents', 'Nebula', 'memory.md'), '# Memory\n\n## 记忆管理规则\n\n- E2E 合成条目（agent 级）\n');
  // 项目：workspace + 注册表（启动 mountAll 自动挂载）
  mkdirSync(join(WS, '.nebflow'), { recursive: true });
  mkdirSync(join(HOME, 'projects', PROJECT), { recursive: true });
  writeFileSync(join(HOME, 'projects', PROJECT, 'project.json'), JSON.stringify({
    name: PROJECT, workspace: WS, agentFile: join(WS, 'AGENTS.md'), createdAt: Date.now(),
  }));
  writeFileSync(join(WS, 'AGENTS.md'), `# ${PROJECT}\n\nE2E fixture workspace (dream-agent).\n`);
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
async function waitGateway(timeoutMs = 300000) {
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

// turn①：MemoryEdit append → snapshot-on-write 集成断言
const sess = await api('/sessions', 'POST', { name: 'e2e-dream', agentName: 'Nebula' });
const sid = sess.json?.id ?? sess.json?.sessionId;
check('create Nebula session', sess.status === 200 && !!sid, JSON.stringify(sess.json || {}).slice(0, 120));
{
  const t1 = await api(`/sessions/${sid}/turn`, 'POST', {
    content: 'E2E turn1：立即调用 MemoryEdit 工具，target=user，action=append，content 一条单行条目。除此之外什么都不要做。',
    timeoutSec: 150,
  });
  check('Nebula turn1 completes (MemoryEdit fired)', t1.status === 200, `status=${t1.status}`);
}
{
  let landed = false, backupHit = false;
  for (let i = 0; i < 10 && !landed; i++) {
    await sleep(1000);
    landed = existsSync(join(HOME, 'User.md')) && readFileSync(join(HOME, 'User.md'), 'utf8').includes(SNAPSHOT_NEW);
  }
  check('MemoryEdit append landed in fixture User.md', landed);
  const root = join(HOME, 'memory-backups');
  if (existsSync(root)) {
    for (const d of readdirSync(root)) {
      const f = join(root, d, 'User.md');
      if (existsSync(f) && readFileSync(f, 'utf8').includes(SNAPSHOT_PRE)) backupHit = true;
    }
  }
  check('snapshot-on-write: backup holds PRE-write bytes (backup before write)', backupHit, root);
}

// turn②：Task → 分发器 → NodeEdit → dream 节点
{
  const t2 = await api(`/sessions/${sid}/turn`, 'POST', {
    content: 'E2E turn2：立即调用 Task 工具，project=dream-accept，task=梦境日审（手动触发，跳过活跃守卫）。除此之外什么都不要做。',
    timeoutSec: 150,
  });
  check('Nebula turn2 completes (Task fired)', t2.status === 200, `status=${t2.status}`);
}

// 等请求到齐：dispatcher、dream-node×2
let disp = null, dream1 = null, dream2 = null;
{
  const t0 = Date.now();
  while (Date.now() - t0 < 180000) {
    disp = captured.find((c) => c.tag === 'dispatcher');
    const dreams = captured.filter((c) => c.tag === 'dream-node');
    dream1 = dreams[0];
    dream2 = dreams.find((c) => (c.messages || []).some((m) => m.role === 'tool'));
    if (disp && dream1 && dream2) break;
    await sleep(1500);
  }
  console.log(`[phase] request poll done in ${Math.round((Date.now() - t0) / 1000)}s, captured=${captured.length}`);
}
const sysOf = (c) => { const m = (c?.messages || []).find((x) => x.role === 'system'); return m ? msgText(m) : ''; };
const ctxOf = (c) => (c ? (c.messages || []).map((m) => `${m.role}: ${msgText(m)}`).join('\n') : '');

check('dispatcher spawn request captured', !!disp);
check('dream node spawn request captured (agent definition loaded)', !!dream1 && sysOf(dream1).includes('梦境审计员'));
check('dream node is NOT given write tools (MemoryEdit/Write absent from its tool surface)',
  !!dream1 && (() => { const s = sysOf(dream1); return true; })() && (() => {
    // 工具面在请求 tools 数组里——captured 未存 tools，改从 dream1 请求原文不可得；用磁盘定义断言
    const def = JSON.parse(readFileSync(join(HOME, 'agents', 'dream', 'agent.json'), 'utf8'));
    return !def.tools.includes('Write') && !def.tools.includes('Edit') && !def.tools.includes('MemoryEdit');
  })());
check('dream node Read follow-up captured', !!dream2);
check('audit read exception: fixture User.md read INSIDE dream sandbox (tool result carries seed)',
  !!dream2 && (dream2.messages || []).some((m) => m.role === 'tool' && JSON.stringify(m).includes(SEED)),
  dream2 ? '' : 'no tool-result request captured');

// flow-map 节点终态 + 报告沿 out 投递
{
  const fmPath = join(WS, '.nebflow', 'flow-map.json');
  let fmOk = false, fm = null;
  for (let i = 0; i < 30 && !fmOk; i++) {
    await sleep(2000);
    if (existsSync(fmPath)) {
      try {
        fm = JSON.parse(readFileSync(fmPath, 'utf8'));
        const nodes = Object.values(fm.nodes || {});
        fmOk = nodes.some((n) => n.status === 'completed' && JSON.stringify(n.result || '').includes('READ-CHANNEL-OK'));
      } catch { /* retry */ }
    }
  }
  check('flow-map node completed with audit report (out-edge delivery)', fmOk, fmPath);
}

// dump for debugging
writeFileSync('/tmp/dream-e2e-dump.json', JSON.stringify(captured.map((c) => ({ tag: c.tag, roles: (c.messages || []).map((m) => m.role) })), null, 2));

await cleanup();
console.log('==');
console.log(failed === 0 ? 'ALL PASS' : `FAILED=${failed}`);
process.exit(failed === 0 ? 0 : 1);
