#!/usr/bin/env node
// e2e-archive-restart-visibility.mjs — 「未完成节点重启归档与状态保真」批 e2e 反事实验收。
//
// 场景（作者 2026-09-07 裁定⑤的反事实）：**真在飞**节点（stub LLM 挂流不回 → 节点
// fiber 存活、status=running）运行中宿主进程被 kill -KILL（强制关闭/崩溃模拟）→
// 同 home 重启 → 断言：
//   ① 死亡节点经 mount reap 收敛真实终态 cancelled 且**留活动区**（主图可见）——
//      不归档、无 TTL（非「-」非缺省）；
//   ② completed 链 TTL 自动归档不回归（过期 completed 被 sweep 进归档区，载荷带 status）；
//   ③ 存量带过期 TTL 的 failed 节点受过滤保护永不 sweep（零迁移口径）；
//   ④ 状态保真：API 载荷恒带非空合法 status；审计事件流留 reaped 痕。
//
// 链路（全真）：Nebula 会话 turn → stub 回 Mail(→demo) → 分发器 spawn → stub 回
// NodeEdit（创建即运行）→ 节点首条消息到 stub → stub 挂流永不回 → 节点真 running。
// 取证口径：磁盘 flow-map.json / flow-map-archive.json / flow-map-events.jsonl
// + REST /api/projects/<n>/flow-map 双源对账。前端渲染保真由
// scripts/verify-flowmap-archive-visibility.cjs（14 断言 + 双主题截图）覆盖。
//
// 隔离纪律（2026-09-05）：java 直启（禁 sbt run）+ 独立 --home + 端口≠8080；
// trap cleanup EXIT/INT/TERM——进程组 kill + lsof 端口复查清零；杀前验身
// （只杀自己 spawn 的进程组；PID≠宿主 43459 硬校验）。
//
// Run（worktree 根，先 sbt compile + export Compile/fullClasspath）：
//   node scripts/e2e-archive-restart-visibility.mjs
// Env：PORT=8111 MOCK_PORT=18111（fixture home 默认**不删**，要清理设 CLEAN=1；NB_DRY_RUN=1 只跑删除守卫断言）

import { spawn, execSync } from 'node:child_process';
import http from 'node:http';
import { readFileSync, writeFileSync, mkdirSync, existsSync, openSync, cpSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { tmpdir, homedir } from 'node:os';
import { setTimeout as sleep } from 'node:timers/promises';
import { guardFixtureHome, safeRm } from './lib/delguard.mjs';

const REPO = dirname(dirname(fileURLToPath(import.meta.url)));
const PORT = Number(process.env.PORT || 8111);
const MOCK_PORT = Number(process.env.MOCK_PORT || 18111);
const BASE = `http://127.0.0.1:${PORT}`;
const HOME = process.env.NEBFLOW_HOME || join(tmpdir(), `nb-arch-e2e-${Date.now()}`);
// R4 删除守卫：解析后的真实绝对路径必须落 tmpdir 之下（≠ tmpdir 自身）——早期 fail-fast，先于任何 spawn。
// NB_DRY_RUN=1：只跑断言、不删除、不 spawn（下游负控入口）。
guardFixtureHome(HOME, { label: 'e2e-archive-restart-visibility' });
const WS = join(HOME, 'ws-demo');
const FM = join(WS, '.nebflow', 'flow-map.json');
const FM_ARCHIVE = join(WS, '.nebflow', 'flow-map-archive.json');
const FM_EVENTS = join(WS, '.nebflow', 'flow-map-events.jsonl');
const CP_FILE = join(REPO, 'target', 'streams', 'compile', 'fullClasspath', '_global', 'streams', 'export');
const DISPATCHER_AGENT_SRC = join(homedir(), '.nebflow', 'agents', 'project-dispatcher');
const HOST_PID = 43459; // 宿主绝对禁杀（双保险：本脚本只 kill 自己 spawn 的进程组）
const NODE_NAME = 'probe-death';
const NODE_MARKER = 'E2E death probe a7f3';

let failed = 0;
function check(name, ok, extra = '') {
  if (!ok) failed++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + String(extra).slice(0, 300) : ''}`);
}

// ── 清理（trap cleanup EXIT 纪律：进程组 kill + 端口复查清零）──
let child = null;
let mockServer = null;
const heldSockets = new Set();
let cleaned = false;
function cleanup() {
  if (cleaned) return;
  cleaned = true;
  for (const s of heldSockets) { try { s.destroy(); } catch { /* gone */ } }
  try { mockServer?.closeAllConnections?.(); mockServer?.close(); } catch { /* already closed */ }
  if (child?.pid && child.pid !== HOST_PID) {
    try { process.kill(-child.pid, 'SIGKILL'); } catch { /* gone */ }
  }
  for (const port of [PORT, MOCK_PORT]) {
    try {
      const pids = execSync(`lsof -tiTCP:${port} -sTCP:LISTEN 2>/dev/null`, { shell: '/bin/bash' }).toString().trim();
      if (pids) for (const p of pids.split('\n').filter(Boolean)) {
        if (Number(p) === HOST_PID) { console.error(`!! 端口 ${port} 命中宿主 PID，拒绝 kill`); continue; }
        try { process.kill(Number(p), 'SIGKILL'); } catch { /* gone */ }
      }
    } catch { /* no listener — good */ }
  }
  // 删除点二次断言；默认不删（要清理设 CLEAN=1）——非 tmp 隔离 home 在此处也会被拒
  safeRm(HOME, { label: 'e2e-archive-restart-visibility' });
}
for (const sig of ['SIGINT', 'SIGTERM', 'uncaughtException', 'unhandledRejection']) {
  process.on(sig, (e) => { if (e) console.error(e); cleanup(); process.exit(1); });
}

// ── stub LLM（OpenAI 兼容流式）：Nebula→Mail、分发器→NodeEdit、节点→挂流不回 ──
let nodeEditFired = false;
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
function startMock() {
  const server = http.createServer((req, res) => {
    if (req.method === 'POST' && (req.url === '/v1/chat/completions' || req.url === '/chat/completions')) {
      let raw = '';
      req.on('data', (d) => (raw += d));
      req.on('end', () => {
        let body = {};
        try { body = JSON.parse(raw); } catch { /* tolerate */ }
        const msgs = body.messages || [];
        const s = JSON.stringify(msgs);
        const last = msgs[msgs.length - 1];
        res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-cache', connection: 'keep-alive' });
        if (last?.role === 'tool') return res.end(finalOk());
        if (s.includes('你是项目') && s.includes('任务分发器')) {
          if (!nodeEditFired) {
            nodeEditFired = true;
            console.log('[stub] dispatcher → NodeEdit(probe-death)');
            return res.end(done([
              chunk({ role: 'assistant', content: '' }),
              chunk(toolCall('call_nodeedit', 'NodeEdit', {
                project: 'demo', nodename: NODE_NAME, description: 'e2e 死亡现场探针节点',
                task: `${NODE_MARKER}: hang forever (stub holds the stream)`,
                out: 'Nebula',
              })),
              chunk({}, 'tool_calls'),
            ]));
          }
          return res.end(finalOk());
        }
        if (s.includes(NODE_MARKER)) {
          // 节点会话：挂流永不回——fiber 存活，节点保持 running 直到宿主被杀
          console.log('[stub] node request held open (node stays running)');
          res.write(chunk({ role: 'assistant', content: '' }));
          heldSockets.add(res.socket);
          res.socket.on('close', () => heldSockets.delete(res.socket));
          return; // never res.end()
        }
        // Nebula 会话：回 Task(project=demo)（2026-09-06 TaskList 批起 Nebula 无
        // Mail——旧体系退役，项目分发的唯一通道 = Task 工具）
        console.log('[stub] nebula → Task(demo)');
        return res.end(done([
          chunk({ role: 'assistant', content: '' }),
          chunk(toolCall('call_task', 'Task', { project: 'demo', task: 'E2E dispatch probe: create the death-probe node' })),
          chunk({}, 'tool_calls'),
        ]));
      });
      return;
    }
    res.writeHead(404).end();
  });
  return new Promise((resolve) => server.listen(MOCK_PORT, '127.0.0.1', () => resolve(server)));
}

// ── fixture home ──
const now = Date.now();
function node(over) {
  return {
    id: '', name: '', agent: 'general', in: [], out: null, deps: [],
    deliveredTo: [], status: 'wiring', createdAt: now, ...over,
  };
}
function seed() {
  mkdirSync(HOME, { recursive: true });
  // 收敛三角色定义全拷（缺 Nebula 定义 → 会话回退非收敛身份，Mail 被工具面过滤；
  // 缺 general → 节点会话无定义）
  for (const a of ['project-dispatcher', 'Nebula', 'general']) {
    cpSync(join(homedir(), '.nebflow', 'agents', a), join(HOME, 'agents', a), { recursive: true });
  }
  writeFileSync(join(HOME, 'nebflow.json'), JSON.stringify({
    llm: { providers: { mock: { baseUrl: `http://127.0.0.1:${MOCK_PORT}/v1/`, apiKey: 'sk-e2e-stub', protocol: 'openai', models: [{ id: 'mock-1', maxTokens: 8192, contextWindow: 128000 }] } } },
  }));
  mkdirSync(join(HOME, 'projects', 'demo'), { recursive: true });
  writeFileSync(join(HOME, 'projects', 'demo', 'project.json'), JSON.stringify({
    name: 'demo', workspace: WS, agentFile: 'AGENTS.md', createdAt: now,
  }));
  mkdirSync(join(WS, '.nebflow'), { recursive: true });
  writeFileSync(join(WS, 'AGENTS.md'), '# demo\n\ne2e fixture project.\n');
  // 预置三类终态对照（createdAt 间隔 >120s 防前端链聚簇串链）；
  // n-run 不预置——由 stub 链真建真跑（挂载 reap 只在重启后对持久化 running 生效，
  // 预置 running 会在第一轮启动即被 reap，达不到「running 中杀宿主」）。
  const nodes = {
    // 存量带过期 TTL 的 failed（修复前写入的遗产）——sweep 过滤保护，永不归档
    'n-fail': node({ id: 'n-fail', name: '实施-遗产失败', status: 'failed', createdAt: now - 300000, completedAt: now - 290000, ttlExpireAt: now - 1000, result: 'legacy failure', description: '遗产 failed 带过期 TTL' }),
    // 过期 completed——TTL sweep 正常归档（回归断言）
    'n-done': node({ id: 'n-done', name: '实施-正常完成', status: 'completed', createdAt: now - 200000, completedAt: now - 190000, ttlExpireAt: now - 1000, result: 'done', description: '过期 completed 应归档' }),
    // 未过期 completed——留在活动区（回归断言）
    'n-live': node({ id: 'n-live', name: '实施-新鲜完成', status: 'completed', createdAt: now - 100000, completedAt: now - 90000, ttlExpireAt: now + 86400000, result: 'live', description: '未过期 completed 保留' }),
  };
  writeFileSync(FM, JSON.stringify({ v: 1, project: 'demo', updatedAt: now, nodes }, null, 2));
}

// ── 隔离实例生命周期 ──
function start() {
  const cp = readFileSync(CP_FILE, 'utf8').trim();
  const log = join(HOME, 'gateway.log');
  const out = openSync(log, 'a');
  child = spawn('java', ['-Xmx1g', '-cp', cp, 'nebflow.Main', '--home', HOME, '--port', String(PORT), '--no-browser', 'start'], {
    cwd: REPO, detached: true, stdio: ['ignore', out, out],
    env: { ...process.env, GATEWAY_PORT: String(PORT), NEBFLOW_GATEWAY_PORT: String(PORT) },
  });
  console.log(`[phase] gateway spawn pid=${child.pid} home=${HOME} port=${PORT} log=${log}`);
  return child;
}
async function token() {
  for (let i = 0; i < 60; i++) {
    const p = join(HOME, 'auth.json');
    if (existsSync(p)) { try { return JSON.parse(readFileSync(p, 'utf8')); } catch { /* mid-write */ } }
    await sleep(500);
  }
  throw new Error('auth.json never appeared');
}
async function api(path, method = 'GET', body) {
  const t = await token();
  const r = await fetch(`${BASE}${path}${path.includes('?') ? '&' : '?'}token=${encodeURIComponent(t)}`, {
    method,
    headers: body ? { 'content-type': 'application/json' } : undefined,
    body: body ? JSON.stringify(body) : undefined,
  });
  return { status: r.status, json: await r.json().catch(() => null) };
}
async function waitReady(timeoutMs = 90000) {
  const t0 = Date.now();
  while (Date.now() - t0 < timeoutMs) {
    try {
      const r = await api('/api/projects');
      if (r.status === 200) { console.log(`[poll] ready after ${Math.round((Date.now() - t0) / 1000)}s`); return true; }
    } catch { /* not yet */ }
    await sleep(1500);
  }
  return false;
}
const readJson = (p) => (existsSync(p) ? JSON.parse(readFileSync(p, 'utf8')) : null);

// ── 主链 ──
if (!existsSync(CP_FILE)) { console.error(`classpath export missing: ${CP_FILE} — 先跑 sbt 'export Compile / fullClasspath'`); process.exit(2); }
mockServer = await startMock();
console.log(`[phase] stub LLM up on :${MOCK_PORT}`);
seed();
console.log(`[phase] fixture seeded at ${HOME}`);

// ── 第一轮：真建节点 → 真 running → kill -KILL ──
start();
if (!(await waitReady())) { console.error('gateway never became ready (round 1)'); cleanup(); process.exit(1); }

const sess = await api('/api/sessions', 'POST', { name: 'e2e-arch', agentName: 'Nebula' });
const sid = sess.json?.id ?? sess.json?.sessionId;
check('P0 建 Nebula 会话', sess.status === 200 && !!sid, `status=${sess.status}`);
const turn = await api(`/api/sessions/${sid}/turn`, 'POST', {
  content: '立即调用 Task 工具，project=demo，task=「E2E dispatch probe」。除此之外什么都不要做。',
  timeoutSec: 150,
});
check('P0 Nebula turn 完成（Task→demo 已发）', turn.status === 200, `status=${turn.status}`);

// 等节点真建真跑（stub 挂流 → fiber 存活）
let runNode = null;
{
  const t0 = Date.now();
  while (Date.now() - t0 < 90000) {
    const r = await api('/api/projects/demo/flow-map');
    runNode = (r.json?.nodes || []).find((n) => n.name === NODE_NAME);
    if (runNode?.status === 'running') break;
    await sleep(1500);
  }
}
check('P1 节点真建真跑：probe-death status=running（stub 挂流在飞）', runNode?.status === 'running', runNode ? `${runNode.id} ${runNode.status}` : 'not found');
if (!runNode) { console.error('node never reached running — abort'); cleanup(); process.exit(1); }
const RUN_ID = runNode.id;

// kill -KILL 宿主模拟（验身：只杀自己 spawn 的进程组，且 PID≠宿主；
// ps 在本沙箱被禁（Operation not permitted）——cwd 验身走 lsof）
const victim = child.pid;
if (!victim || victim === HOST_PID) { console.error(`!! victim pid 异常: ${victim}`); cleanup(); process.exit(1); }
const victimCwd = execSync(`lsof -a -p ${victim} -d cwd -Fn 2>/dev/null || true`, { shell: '/bin/bash' }).toString();
if (!victimCwd.includes('nb-arch-e2e') && !victimCwd.includes('worktrees')) { console.error(`!! victim cwd 不符: ${victimCwd.slice(0, 160)}`); cleanup(); process.exit(1); }
try { process.kill(-victim, 'SIGKILL'); } catch { try { process.kill(victim, 'SIGKILL'); } catch { /* gone */ } }
console.log(`[phase] kill -KILL pid=${victim}（running 中宿主死亡模拟，节点 ${RUN_ID} 在飞）`);
for (let i = 0; i < 30; i++) {
  let free = false;
  try { execSync(`lsof -tiTCP:${PORT} -sTCP:LISTEN`, { shell: '/bin/bash', stdio: 'pipe' }); } catch { free = true; }
  if (free) break;
  await sleep(500);
}
child = null;

// ── 第二轮：同 home 重启 ──
start();
if (!(await waitReady())) { console.error('gateway never became ready (round 2)'); cleanup(); process.exit(1); }

// 等 TtlTick（30s 周期）跑过至少一轮再断言 sweep 行为
console.log('[phase] waiting ~40s for mount reap + first TtlTick sweep…');
await sleep(40000);

const disk = readJson(FM);
const arch = readJson(FM_ARCHIVE);
const events = existsSync(FM_EVENTS) ? readFileSync(FM_EVENTS, 'utf8') : '';
const post = await api('/api/projects/demo/flow-map');
const postNodes = Object.fromEntries((post.json?.nodes || []).map((n) => [n.id, n]));

// ① 死亡现场保留：cancelled + 留活动区 + 无 TTL + 不归档
check('① 磁盘：死亡节点收敛 cancelled（mount reap）', disk?.nodes?.[RUN_ID]?.status === 'cancelled', disk?.nodes?.[RUN_ID]?.status);
check('① 磁盘：死亡节点无 ttlExpireAt（无 TTL 强制清——2026-09-07 裁定）', !disk?.nodes?.[RUN_ID]?.ttlExpireAt, disk?.nodes?.[RUN_ID]?.ttlExpireAt ?? 'null');
check('① API：死亡节点在主图活动区可见，status=cancelled（非「-」非缺省）', postNodes[RUN_ID]?.status === 'cancelled', postNodes[RUN_ID]?.status);
check('① 归档区零死亡节点：死亡节点 ∉ flow-map-archive.json', !arch?.nodes?.[RUN_ID]);
check('① 审计留痕：flow-map-events.jsonl 含死亡节点 reaped 事件',
  events.split('\n').some((l) => l.includes(RUN_ID) && l.includes('reaped')));

// ② completed TTL 自动归档不回归
check('② n-done（过期 completed）被 sweep 出活动区', !disk?.nodes?.['n-done'] && !postNodes['n-done']);
check('② n-done 进归档区且载荷带 status=completed（保真）', arch?.nodes?.['n-done']?.status === 'completed', arch?.nodes?.['n-done']?.status);
check('② n-live（未过期 completed）保留活动区', disk?.nodes?.['n-live']?.status === 'completed' && postNodes['n-live']?.status === 'completed');

// ③ 遗产 failed 带过期 TTL——过滤保护，永不 sweep
check('③ n-fail（遗产 failed+过期 TTL）仍留活动区', disk?.nodes?.['n-fail']?.status === 'failed' && postNodes['n-fail']?.status === 'failed');
check('③ n-fail ∉ 归档区（Terminal→Completed 过滤收窄生效）', !arch?.nodes?.['n-fail']);

// ④ 状态保真（载荷层）：API 全部节点 status 非空且为合法词
const statuses = (post.json?.nodes || []).map((n) => n.status);
check('④ API 载荷 status 全非空非「-」', statuses.length > 0 && statuses.every((s) => typeof s === 'string' && s.length > 0 && s !== '-'), statuses.join(','));

const total = 13;
console.log(`\n${failed === 0 ? 'ALL PASS' : 'FAILURES PRESENT'} — ${total - failed}/${total}`);
cleanup();
process.exit(failed ? 1 : 0);
