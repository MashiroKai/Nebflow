#!/usr/bin/env node
// smoke-nodename-refresh.mjs — 节点名刷新持久化 e2e 冒烟（20260907）。
//
// 验收链（真实全环，e2e-dispatcher-context-catalog.mjs 同款配方）：隔离
// NEBFLOW_HOME + 隔离实例（sbt run --home/--port，绝非 8080 宿主）+ OpenAI 兼容
// stub LLM（内嵌，分流：Nebula 触发 / 分发器 NodeEdit / 节点挂起慢流）。
//   1. Nebula 会话 REST turn → Task(→e2e-proj) → 分发器 spawn
//   2. 分发器 stub 应答 NodeEdit 工具调用——创建入口节点（自定义名
//      「实施-节点命名验证」，task 带挂起标记，out=Nebula）→ 节点立即运行
//   3. 节点会话（general agent）打到 stub → 慢流滴答（15s/chunk）保持 running
//   4. 断言（页面刷新语义 = 新 WS 连接 getActiveAgents，与前端 activeAgents
//      handler 同帧）：
//      ① REST flow-map 载荷 node.name = 自定义名（Flow Map 卡片渲染源）
//      ② WS getActiveAgents → node- 条目 agentName = 自定义名
//        （subagent 面板刷新恢复源——修复前此处回退 sessionId「node-xx」）
//      ③ live agentStart 帧 taskDescription = 自定义名（实时路径回归守卫）
//      ④ live nodeCreated/Updated 帧载荷 name = 自定义名（卡片 WS 增量源）
//      ⑤ 磁盘 workspace/.nebflow/flow-map.json name = 自定义名（持久化）
//
// Run（worktree 根）：
//   node scripts/smoke-nodename-refresh.mjs
// Env：KEEP=1 保留 fixture home；GATEWAY_PORT/MOCK_PORT 可覆盖；
//      NEGFIX_EXPECT=legacy 时按修复前口径断言（负向对照用）。

import { spawn, execSync } from 'node:child_process';
import http from 'node:http';
import { readFileSync, mkdirSync, writeFileSync, cpSync, rmSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { homedir, tmpdir } from 'node:os';
import { setTimeout as sleep } from 'node:timers/promises';

const REPO = dirname(dirname(fileURLToPath(import.meta.url)));
const GATEWAY_PORT = Number(process.env.GATEWAY_PORT || 8093);
const MOCK_PORT = Number(process.env.MOCK_PORT || 18993);
const BASE = `http://127.0.0.1:${GATEWAY_PORT}`;
const HOME = process.env.NEBFLOW_HOME || join(tmpdir(), `nb-nodename-e2e-${Date.now()}`);
const AGENTS_SRC = join(homedir(), '.nebflow', 'agents');

const NODE_NAME = '实施-节点命名验证';
const TRIGGER_MARK = 'E2E_NODENAME_DISPATCH';
const HANG_MARK = 'E2E_NODENAME_HANG';
// 修复口径：刷新恢复 agentName=节点名；legacy 口径（负向对照）：回退 sessionId
const EXPECT_LEGACY = process.env.NEGFIX_EXPECT === 'legacy';

let failed = 0;
function check(name, ok, extra = '') {
  if (!ok) failed++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
}

// ── 清理（trap cleanup EXIT 纪律：后台 PID 登记 → EXIT 逐 kill + 端口复查）──
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
process.on('exit', () => { /* sync kill 兜底（detached 组 kill 已在 async cleanup） */ });

// ── stub LLM（OpenAI 兼容流式；按请求分流）──
const captured = [];
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
/** 分流：node 会话（挂起慢流）/ 分发器（NodeEdit 工具调用→终答）/ Nebula 触发 / 其他。 */
function classify(body) {
  const s = JSON.stringify(body.messages);
  const isDispatcher = s.includes('你是项目') && s.includes('任务分发器');
  const userMsgs = (body.messages || []).filter((m) => m.role === 'user');
  const isNodeHang = !isDispatcher && userMsgs.some((m) => msgText(m).includes(HANG_MARK));
  // 触发判定只认「最后一条消息就是 user」（工具结果轮的 last-user 仍是触发句——
  // 若按任意 last-user 匹配会把每轮工具结果又答成 Task 调用 → 循环直到底层 loop-guard）
  const lastMsg = (body.messages || [])[(body.messages || []).length - 1];
  const isTrigger = !isDispatcher && !isNodeHang && lastMsg?.role === 'user' && msgText(lastMsg).includes(TRIGGER_MARK);
  return { isDispatcher, isNodeHang, isTrigger };
}
/** 分发器首请求（无 tool 结果）→ NodeEdit 创建入口节点；工具结果轮 → 终答。 */
function dispatcherEvents(body) {
  const hasToolResult = (body.messages || []).some((m) => m.role === 'tool');
  if (!hasToolResult) {
    const args = JSON.stringify({
      project: 'e2e-proj',
      nodename: NODE_NAME,
      description: 'e2e：验证节点名经页面刷新保持（Flow Map 卡片 + subagent 面板）',
      task: `${HANG_MARK}：保持运行等待刷新断言，不要提前结束。`,
      out: 'Nebula',
    });
    return [
      chunk({ role: 'assistant', content: '' }),
      chunk({ tool_calls: [{ index: 0, id: 'call_stub_nodeedit', type: 'function', function: { name: 'NodeEdit', arguments: args } }] }),
      chunk({}, 'tool_calls'),
      'data: [DONE]\n\n',
    ];
  }
  return [chunk({ role: 'assistant', content: '' }), chunk({ content: 'ok' }), chunk({}, 'stop'), 'data: [DONE]\n\n'];
}
function startMock() {
  const server = http.createServer((req, res) => {
    if (req.method === 'POST' && (req.url === '/v1/chat/completions' || req.url === '/chat/completions')) {
      let bodyRaw = '';
      req.on('data', (d) => (bodyRaw += d));
      req.on('end', () => {
        let body = {};
        try { body = JSON.parse(bodyRaw); } catch { /* tolerate */ }
        captured.push({ url: req.url, messages: body.messages || [], ts: Date.now() });
        const { isDispatcher, isNodeHang, isTrigger } = classify(body);
        if (isNodeHang) {
          // 节点会话：首 chunk 立即（过 90s 首 token 看门狗）+ 15s 滴答（防流内
          // inactivity 看门狗）——保持节点 running 供刷新断言；永不 [DONE]。
          res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-cache', connection: 'keep-alive' });
          res.write(chunk({ role: 'assistant', content: '' }) + chunk({ content: '…运行中' }));
          const timer = setInterval(() => {
            try { res.write(chunk({ content: '…' })); } catch { clearInterval(timer); }
          }, 15000);
          req.on('close', () => clearInterval(timer));
          return;
        }
        let events;
        if (isDispatcher) {
          events = dispatcherEvents(body);
        } else if (isTrigger) {
          const args = JSON.stringify({ project: 'e2e-proj', task: 'E2E nodename refresh probe: 分发器建节点验证刷新保持' });
          events = [
            chunk({ role: 'assistant', content: '' }),
            chunk({ tool_calls: [{ index: 0, id: 'call_stub_task', type: 'function', function: { name: 'Task', arguments: args } }] }),
            chunk({}, 'tool_calls'),
            'data: [DONE]\n\n',
          ];
        } else if ((body.messages[body.messages.length - 1] || {}).role === 'tool') {
          events = [chunk({ role: 'assistant', content: '' }), chunk({ content: 'ok' }), chunk({}, 'stop'), 'data: [DONE]\n\n'];
        } else {
          events = [chunk({ role: 'assistant', content: '' }), chunk({ content: 'ok' }), chunk({}, 'stop'), 'data: [DONE]\n\n'];
        }
        res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-cache', connection: 'keep-alive' });
        res.end(events.join(''));
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
  const ws = join(HOME, 'ws-e2e-proj');
  // agent 真身拷贝（EntityLoader.loadAgent 需要）：project-dispatcher + general（节点执行体）
  cpSync(join(AGENTS_SRC, 'project-dispatcher'), join(HOME, 'agents', 'project-dispatcher'), { recursive: true });
  cpSync(join(AGENTS_SRC, 'general'), join(HOME, 'agents', 'general'), { recursive: true });
  // model-presets.json：defaultPreset=general → mock/mock-1（Nebula/分发器/节点三链同源）
  writeFileSync(join(HOME, 'model-presets.json'), JSON.stringify({
    defaultPreset: 'general',
    presets: { general: { name: 'general', description: '', preferred: 'mock/mock-1', fallbacks: [] } },
  }));
  // nebflow.json：provider → stub（ModelConfig deriveDecoder 无默认值回退——id/maxTokens/contextWindow 必须显式）
  writeFileSync(join(HOME, 'nebflow.json'), JSON.stringify({
    llm: { providers: { mock: { baseUrl: `http://127.0.0.1:${MOCK_PORT}/v1/`, apiKey: 'sk-e2e-stub', protocol: 'openai', models: [{ id: 'mock-1', maxTokens: 8192, contextWindow: 128000 }] } } },
  }));
  // 项目：启动 mountAll 自动挂载
  mkdirSync(ws, { recursive: true });
  mkdirSync(join(HOME, 'projects', 'e2e-proj'), { recursive: true });
  writeFileSync(join(HOME, 'projects', 'e2e-proj', 'project.json'), JSON.stringify({
    name: 'e2e-proj', workspace: ws, agentFile: join(ws, 'AGENTS.md'), createdAt: Date.now(),
  }));
  writeFileSync(join(ws, 'AGENTS.md'), '# e2e-proj\n\nE2E fixture workspace (nodename-refresh).\n');
}

// ── 隔离实例（sbt run --home/--port；命令串单参数传 sbt）──
// boot 目录隔离已随拆围栏退役（2026-09-10）：原先注入的两个 sbt sysprop
// （boot.directory=/tmp/nb-sbt-boot-nodename、global.base=/tmp/nb-sbt-base-nodename）是
// 绕 ~/.sbt EPERM 写锁的绕行；拆围栏 + 宿主重启后 plain sbt 可直写 ~/.sbt
// （T2 探针 2026-09-10 17:30 CST：env -u SBT_OPTS -u COURSIER_CACHE sbt -batch "print name" ⇒ exit=0），
// 故此处只保留 -Xmx3g（大 sbt 会话内存）。注：sbt run 调用形态未改（单派项）。
function startGateway() {
  const cmd = `run --home ${HOME} --port ${GATEWAY_PORT} --no-browser`;
  const sbt = spawn('sbt', ['-batch', cmd], {
    cwd: REPO, detached: true, stdio: ['ignore', 'pipe', 'pipe'],
    env: {
      ...process.env,
      SBT_OPTS: (process.env.SBT_OPTS || '') + ` -Xmx3g`,
    },
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

// ── WS 客户端（smoke-agentcontrol-ws.mjs 同款）──
function wsConnect() {
  return new Promise((resolve, reject) => {
    const TOKEN = JSON.parse(readFileSync(join(HOME, 'auth.json'), 'utf8'));
    const ws = new WebSocket(`${BASE.replace('http', 'ws')}/ws?token=${encodeURIComponent(TOKEN)}`);
    const inbox = [];
    const waiters = [];
    ws.addEventListener('message', (ev) => {
      let msg;
      try { msg = JSON.parse(ev.data); } catch { return; }
      inbox.push(msg);
      for (let i = waiters.length - 1; i >= 0; i--) {
        const w = waiters[i];
        if (w.pred(msg)) { waiters.splice(i, 1); w.resolve(msg); }
      }
    });
    ws.addEventListener('open', () => resolve({ ws, inbox, waiters }));
    ws.addEventListener('error', (e) => reject(new Error('WS connect failed: ' + (e.message || 'error'))));
  });
}
function waitFor(conn, pred, timeoutMs, label) {
  const hit = conn.inbox.find(pred);
  if (hit) return Promise.resolve(hit);
  return new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error(`timeout waiting for ${label}`)), timeoutMs);
    conn.waiters.push({ pred, resolve: (m) => { clearTimeout(t); resolve(m); } });
  });
}

async function api(path, method = 'GET', body) {
  const TOKEN = JSON.parse(readFileSync(join(HOME, 'auth.json'), 'utf8'));
  const res = await fetch(`${BASE}/api${path}`, {
    method,
    headers: { authorization: `Bearer ${TOKEN}`, 'content-type': 'application/json' },
    body: body ? JSON.stringify(body) : undefined,
  });
  return { status: res.status, json: await res.json().catch(() => null), text: await res.text().catch(() => '') };
}

// ── 主链 ──
mockServer = await startMock();
console.log(`[phase] stub LLM up on :${MOCK_PORT}`);
buildFixture();
console.log(`[phase] fixture built at ${HOME}`);
startGateway();
if (!(await waitGateway())) {
  console.log('FAIL  gateway never came up — aborting (sbt passthrough above has the instance error)');
  await cleanup();
  process.exit(1);
}
check('isolated gateway up', true, BASE);

// live 帧捕获 WS：先连后触发（agentStart/nodeCreated/nodeUpdated 全程在窗内）
const liveConn = await wsConnect();
check('live-capture WS connected', true);

// 1. Nebula 会话 + REST turn → Task(→e2e-proj) → 分发器 → NodeEdit 建节点
const sess = await api('/sessions', 'POST', { name: 'e2e-nodename', agentName: 'Nebula' });
check('create Nebula session', sess.status === 200 && !!(sess.json?.id ?? sess.json?.sessionId), JSON.stringify(sess.json || {}).slice(0, 120));
const sid = sess.json?.id ?? sess.json?.sessionId;
const turn = await api(`/sessions/${sid}/turn`, 'POST', {
  content: `${TRIGGER_MARK}：请立即调用 Task 工具，project=e2e-proj，task=「E2E nodename refresh probe: 分发器建节点验证刷新保持」。除此之外什么都不要做。`,
  timeoutSec: 180,
});
check('Nebula turn completes (Task fired inside)', turn.status === 200, `status=${turn.status}`);

// 2. 等节点 spawn + 翻转 running（REST flow-map 出现自定义名节点且 running）
let nodePayload = null;
{
  const t0 = Date.now();
  while (Date.now() - t0 < 120000) {
    const fm = await api('/projects/e2e-proj/flow-map');
    const nodes = fm.json?.nodes || [];
    const hit = nodes.find((n) => n.name === NODE_NAME);
    if (hit && hit.status === 'running') { nodePayload = hit; break; }
    await sleep(2000);
  }
  console.log(`[phase] node-running poll done in ${Math.round((Date.now() - t0) / 1000)}s`);
}
check('① Flow Map REST payload: node exists, running, name = 自定义名',
  !!nodePayload && nodePayload.name === NODE_NAME,
  nodePayload ? `id=${nodePayload.id} status=${nodePayload.status}` : 'node never reached running');

// 3. live 帧（实时路径回归守卫）
if (nodePayload) {
  try {
    const startFrame = await waitFor(liveConn,
      (m) => m.type === 'agentStart' && (m.agentId || '').startsWith('node-'), 30000, 'live agentStart (node session)');
    check('③ live agentStart 帧到达（节点会话进面板）', !!startFrame, `agentId=${startFrame.agentId}`);
    check('③ live agentStart taskDescription = 自定义名（实时行含节点名）',
      startFrame.taskDescription === NODE_NAME, `taskDescription=${JSON.stringify(startFrame.taskDescription)}`);
    const createdFrame = await waitFor(liveConn,
      (m) => (m.type === 'nodeCreated' || m.type === 'nodeUpdated') && m.node?.name === NODE_NAME, 10000, 'nodeCreated/Updated frame');
    check('④ WS node 事件载荷 name = 自定义名（卡片增量源）',
      createdFrame?.node?.name === NODE_NAME, `type=${createdFrame?.type}`);
  } catch (e) {
    check('③ live frames', false, e.message);
  }
}

// 4. 页面刷新语义：全新 WS 连接 → getActiveAgents（与前端刷新恢复同帧）
const refreshConn = await wsConnect();
try {
  refreshConn.ws.send(JSON.stringify({ type: 'getActiveAgents' }));
  const snap = await waitFor(refreshConn, (m) => m.type === 'activeAgents', 10000, 'activeAgents');
  const agents = snap.agents || [];
  const nodeEntry = agents.find((a) => (a.agentId || a.sessionId || '').startsWith('node-'));
  check('② refresh snapshot contains running node session', !!nodeEntry,
    `agents=[${agents.map((a) => `${a.agentId}:${a.agentName}`).join(', ')}]`);
  if (nodeEntry) {
    if (EXPECT_LEGACY) {
      check('② [legacy 口径] agentName 回退 sessionId（负向对照）',
        nodeEntry.agentName === nodeEntry.agentId,
        `agentName=${JSON.stringify(nodeEntry.agentName)}`);
    } else {
      check('② subagent 面板刷新恢复源 agentName = 自定义名（修复主断言）',
        nodeEntry.agentName === NODE_NAME,
        `agentName=${JSON.stringify(nodeEntry.agentName)}（sessionId=${nodeEntry.agentId}）`);
    }
  }
  // 分发器行（若仍注册）：agentName = dispatcher/<project>（不强制——单次会话可能已终态注销）
  const dispEntry = agents.find((a) => (a.agentId || '').startsWith('dispatcher-'));
  if (dispEntry && !EXPECT_LEGACY) {
    check('② dispatcher 行恢复 agentName = dispatcher/e2e-proj（在场才断言）',
      dispEntry.agentName === 'dispatcher/e2e-proj', `agentName=${JSON.stringify(dispEntry.agentName)}`);
  } else {
    console.log('NOTE  dispatcher session already terminal/unregistered — skip its row assertion');
  }
} catch (e) {
  check('② refresh getActiveAgents', false, e.message);
} finally {
  refreshConn.ws.close();
}

// 5. 持久化：磁盘 flow-map.json name
try {
  const diskFm = JSON.parse(readFileSync(join(HOME, 'ws-e2e-proj', '.nebflow', 'flow-map.json'), 'utf8'));
  const diskNode = Object.values(diskFm.nodes || {}).find((n) => n.name === NODE_NAME);
  check('⑤ 磁盘 flow-map.json 持久化 name = 自定义名', !!diskNode,
    diskNode ? `id=${diskNode.id}` : `nodes=${Object.keys(diskFm.nodes || {}).length}`);
} catch (e) {
  check('⑤ disk flow-map.json', false, e.message);
}

// 证据 dump（无条件落盘，供窗前合并节点取证）
try {
  writeFileSync('/tmp/nodename-e2e-evidence.json', JSON.stringify({
    nodeName: NODE_NAME, expectLegacy: EXPECT_LEGACY, capturedCount: captured.length, nodePayload,
  }, null, 2));
} catch { /* best effort */ }

liveConn.ws.close();
await cleanup();
console.log(failed === 0 ? '\nALL PASS' : `\n${failed} CHECK(S) FAILED`);
process.exit(failed === 0 ? 0 : 1);
