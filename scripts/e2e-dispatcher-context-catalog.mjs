#!/usr/bin/env node
// e2e-dispatcher-context-catalog.mjs — dispatcher-ctx 批 E2E（2026-09-05）。
//
// 验收链（真实全环）：隔离 NEBFLOW_HOME + 隔离实例（sbt run --home/--port，
// 绝非 8080 宿主）+ OpenAI 兼容 stub LLM（内嵌，捕获全部请求）。
//   1. fixture：装 2 插件（cap-a 受信含 capability / beta-untrusted 从不审批）
//      + 2 preset（deep-analyze 含 description / general 无 description）
//   2. Nebula 会话 REST turn → stub 让 Nebula 调 Mail(→e2e-proj)
//      → ProjectActor.TriggerDispatcher → 分发器 spawn
//      → newTaskPrompt（插件能力目录 + 预设场景目录）打到 stub
//   3. 断言：cap-a capability 行出现；beta-untrusted 不出现；
//      preset 场景行出现；无 description 的 preset 只出 name
//   4. token 量化：两段目录字符数 + CJK/ASCII 拆分估算 token（stdout 出证）
//
// Run（worktree 根）：
//   node scripts/e2e-dispatcher-context-catalog.mjs
// Env：KEEP=1 保留 fixture home（默认跑完即删）；GATEWAY_PORT/MOCK_PORT 可覆盖。

import { spawn, execSync } from 'node:child_process';
import http from 'node:http';
import { readFileSync, mkdirSync, writeFileSync, cpSync, rmSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { homedir, tmpdir } from 'node:os';
import { setTimeout as sleep } from 'node:timers/promises';

const REPO = dirname(dirname(fileURLToPath(import.meta.url)));
const GATEWAY_PORT = Number(process.env.GATEWAY_PORT || 8097);
const MOCK_PORT = Number(process.env.MOCK_PORT || 18997);
const BASE = `http://127.0.0.1:${GATEWAY_PORT}`;
const HOME = process.env.NEBFLOW_HOME || join(tmpdir(), `nb-dctx-e2e-${Date.now()}`);
const DISPATCHER_AGENT_SRC = process.env.DISPATCHER_AGENT_SRC || join(homedir(), '.nebflow', 'agents', 'project-dispatcher');

let failed = 0;
function check(name, ok, extra = '') {
  if (!ok) failed++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
}

// ── 清理（trap cleanup EXIT 纪律：后台 PID 登记 → EXIT 逐 kill + wait + 端口复查）──
const children = [];
let cleaned = false;
let mockServer = null; // 前置声明：信号可能早于主链触发 cleanup
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
process.on('exit', () => { /* sync part only; explicit await below */ });
for (const sig of ['SIGINT', 'SIGTERM', 'uncaughtException', 'unhandledRejection']) {
  process.on(sig, async (e) => { if (e) console.error(e); await cleanup(); process.exit(1); });
}

// ── stub LLM（OpenAI 兼容流式；捕获全部请求）──
const captured = []; // {url, messages, ts}
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
function streamFor(body) {
  const s = JSON.stringify(body.messages);
  const isDispatcher = s.includes('你是项目') && s.includes('任务分发器');
  const last = body.messages[body.messages.length - 1];
  const lastUser = [...body.messages].reverse().find((m) => m.role === 'user');
  let events;
  if (isDispatcher || (last?.role === 'tool')) {
    // 分发器上下文请求或工具结果待处理轮 → 终答（防 Mail 工具调用死循环）
    events = [chunk({ role: 'assistant', content: '' }), chunk({ content: 'ok' }), chunk({}, 'stop'), 'data: [DONE]\n\n'];
  } else if (lastUser && msgText(lastUser).includes('E2E_MAIL_TRIGGER')) {
    const args = JSON.stringify({ address: 'e2e-proj', message: 'E2E dispatch probe task: 验证分发器上下文目录注入', type: 'PARALLEL' });
    events = [
      chunk({ role: 'assistant', content: '' }),
      chunk({ tool_calls: [{ index: 0, id: 'call_stub_mail', type: 'function', function: { name: 'Mail', arguments: args } }] }),
      chunk({}, 'tool_calls'),
      'data: [DONE]\n\n',
    ];
  } else {
    events = [chunk({ role: 'assistant', content: '' }), chunk({ content: 'ok' }), chunk({}, 'stop'), 'data: [DONE]\n\n'];
  }
  return { events: events.join(''), isDispatcher };
}
function startMock() {
  const server = http.createServer((req, res) => {
    if (req.method === 'POST' && (req.url === '/v1/chat/completions' || req.url === '/chat/completions')) {
      let bodyRaw = '';
      req.on('data', (d) => (bodyRaw += d));
      req.on('end', () => {
        let body = {};
        try { body = JSON.parse(bodyRaw); } catch { /* tolerate */ }
        const { events } = streamFor(body);
        captured.push({ messages: body.messages || [], ts: Date.now() });
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
  const ws = join(HOME, 'ws-e2e-proj');
  // 分发器 agent 定义（真身拷贝——EntityLoader.loadAgent 需要）
  cpSync(DISPATCHER_AGENT_SRC, join(HOME, 'agents', 'project-dispatcher'), { recursive: true });
  // 插件 cap-a：capability 字段 + skills（审批 → 目录应出现）
  const capA = join(HOME, 'plugins', 'cap-a');
  mkdirSync(join(capA, 'skills', 'probe'), { recursive: true });
  writeFileSync(join(capA, 'plugin.json'), JSON.stringify({
    $schema: 'https://agent-plugins.org/schemas/1.0.0/plugin.schema.json', name: 'cap-a', version: '1.0.0',
    description: 'cap-a 结构描述句（有 capability 时不应出现在目录）',
    capability: 'E2E 能力探针：节点获得分发器目录注入链路的验证能力',
  }, null, 2));
  writeFileSync(join(capA, 'skills', 'probe', 'SKILL.md'), '---\nname: probe\ndescription: probe skill\n---\n# probe\nbody');
  // 插件 beta-untrusted：含 capability 但从不审批 → 目录不得出现
  const beta = join(HOME, 'plugins', 'beta-untrusted');
  mkdirSync(join(beta, 'skills', 'never'), { recursive: true });
  writeFileSync(join(beta, 'plugin.json'), JSON.stringify({
    $schema: 'https://agent-plugins.org/schemas/1.0.0/plugin.schema.json', name: 'beta-untrusted', version: '1.0.0',
    description: '未信任插件（不应出现）',
    capability: '未信任能力句（不应出现）',
  }, null, 2));
  writeFileSync(join(beta, 'skills', 'never', 'SKILL.md'), '---\nname: never\ndescription: never skill\n---\n# never\nbody');
  // model-presets.json：含/不含 description 各一（defaultPreset 必须有链——repair 不动它）
  writeFileSync(join(HOME, 'model-presets.json'), JSON.stringify({
    defaultPreset: 'general',
    presets: {
      general: { name: 'general', description: '', preferred: 'mock/mock-1', fallbacks: [] },
      'deep-analyze': { name: 'deep-analyze', description: '深度分析场景：调研/审阅/方案设计节点适用', preferred: 'mock/mock-1', fallbacks: [] },
    },
  }));
  // nebflow.json：provider → stub + plugins 总闸开
  // （ModelConfig 用 deriveDecoder 无默认值回退——id/maxTokens/contextWindow 必须显式）
  writeFileSync(join(HOME, 'nebflow.json'), JSON.stringify({
    llm: { providers: { mock: { baseUrl: `http://127.0.0.1:${MOCK_PORT}/v1/`, apiKey: 'sk-e2e-stub', protocol: 'openai', models: [{ id: 'mock-1', maxTokens: 8192, contextWindow: 128000 }] } } },
    plugins: { enabled: true },
  }));
  // 项目：启动 mountAll 自动挂载
  mkdirSync(ws, { recursive: true });
  mkdirSync(join(HOME, 'projects', 'e2e-proj'), { recursive: true });
  writeFileSync(join(HOME, 'projects', 'e2e-proj', 'project.json'), JSON.stringify({
    name: 'e2e-proj', workspace: ws, agentFile: join(ws, 'AGENTS.md'), createdAt: Date.now(),
  }));
  writeFileSync(join(ws, 'AGENTS.md'), '# e2e-proj\n\nE2E fixture workspace (dispatcher-context-catalog).\n');
}

// ── 隔离实例（sbt run --home/--port，AGENTS.md 隔离参数款；命令串必须单参数传给 sbt）──
function startGateway() {
  const cmd = `run --home ${HOME} --port ${GATEWAY_PORT} --no-browser`;
  const sbt = spawn('sbt', ['-batch', cmd], {
    cwd: REPO, detached: true, stdio: ['ignore', 'pipe', 'pipe'],
    env: { ...process.env, SBT_OPTS: (process.env.SBT_OPTS || '') + ' -Xmx3g' },
  });
  children.push(sbt);
  // 心跳/透传（后台运行 30s 无输出会被判定挂起）：sbt 关键行 + 全部 stderr
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

// ── token 量化（CJK/ASCII 拆分估算：CJK≈0.66 token/字，ASCII≈0.25 token/字符）──
function tokenEstimate(text) {
  const cjk = (text.match(/[\u3000-\u9fff\uff00-\uffef]/g) || []).length;
  const ascii = text.length - cjk;
  return { chars: text.length, cjk, ascii, tokensEst: Math.round(cjk * 0.66 + ascii * 0.25) };
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

const TOKEN = JSON.parse(readFileSync(join(HOME, 'auth.json'), 'utf8'));
async function api(path, method = 'GET', body) {
  const res = await fetch(`${BASE}/api${path}`, {
    method,
    headers: { authorization: `Bearer ${TOKEN}`, 'content-type': 'application/json' },
    body: body ? JSON.stringify(body) : undefined,
  });
  return { status: res.status, json: await res.json().catch(() => null), text: await res.text().catch(() => '') };
}

// 1. 审批 cap-a（beta-untrusted 保持默认拒绝）
const appr = await api('/plugins/cap-a/approve', 'POST');
check('approve cap-a', appr.status === 200, JSON.stringify(appr.json || appr.text).slice(0, 120));

// 2. Nebula 会话 + REST turn → Mail(→e2e-proj)
const sess = await api('/sessions', 'POST', { name: 'e2e-dctx', agentName: 'Nebula' });
check('create Nebula session', sess.status === 200 && !!(sess.json?.id ?? sess.json?.sessionId), JSON.stringify(sess.json || {}).slice(0, 120));
const sid = sess.json?.id ?? sess.json?.sessionId;
const turn = await api(`/sessions/${sid}/turn`, 'POST', {
  content: 'E2E_MAIL_TRIGGER：请立即调用 Mail 工具，address=e2e-proj，发送消息「E2E dispatch probe task: 验证分发器上下文目录注入」（type=PARALLEL）。除此之外什么都不要做。',
  timeoutSec: 150,
});
check('Nebula turn completes (Mail fired inside)', turn.status === 200, `status=${turn.status}`);

// ── 等分发器 spawn 的 LLM 请求到达 stub ──
// 匹配必须双标记：'你是项目' + '任务分发器'（newTaskPrompt 首句）。单查
// '任务分发器' 会误中 Nebula 系统提示词（其 standalone-agents 目录段含
// 「project-dispatcher: 项目任务分发器…」字样——E2E 初版踩坑）
const isDispatchCtx = (c) => {
  const s = JSON.stringify(c.messages);
  return s.includes('你是项目') && s.includes('任务分发器');
};
let dispatchReq = null;
{
  const t0 = Date.now();
  while (Date.now() - t0 < 30000) {
    dispatchReq = captured.find(isDispatchCtx);
    if (dispatchReq) break;
    await sleep(1000);
  }
  console.log(`[phase] dispatcher-request poll done in ${Math.round((Date.now() - t0) / 1000)}s, captured=${captured.length}`);
}
check('dispatcher spawn request captured by stub', !!dispatchReq);

if (dispatchReq) {
  // 证据 dump（无条件落盘，供诊断与 spec 取证）
  writeFileSync('/tmp/dctx-dispatch-dump.json', JSON.stringify(dispatchReq, null, 2));
  const preview = dispatchReq.messages.map((m) => `${m.role}: ${msgText(m)}`).join('\n');
  console.log('[dump] dispatcher context first 1500 chars:\n' + preview.slice(0, 1500));
  try {
    const cat = await api('/plugins/catalog');
    console.log('[probe] GET /api/plugins/catalog → ' + JSON.stringify(cat.json).slice(0, 500));
  } catch (e) { console.log('[probe] catalog probe failed: ' + e.message); }
  const ctx = dispatchReq.messages.map((m) => `${m.role}: ${msgText(m)}`).join('\n');

  check('cap-a capability line present', ctx.includes('- cap-a: E2E 能力探针：节点获得分发器目录注入链路的验证能力'));
  check('cap-a description suppressed (capability 优先)', !ctx.includes('cap-a 结构描述句'));
  check('untrusted plugin absent', !ctx.includes('beta-untrusted'));
  check('preset scene line present', ctx.includes('- deep-analyze — 深度分析场景：调研/审阅/方案设计节点适用'));
  check('description-less preset renders name only', /(^|\n)- general(\n|$)/.test(ctx) && !ctx.includes('general —'));
  check('plugin section header present', ctx.includes('\n# Plugin Catalog'));
  check('preset section header present', ctx.includes('\n# Model Preset Catalog'));

  // token 量化（两段目录各自取段；锚点带行首 \n——避免命中分发器 system.md
  // 的「## Plugin Catalog 认知」小节标题（其含 '# Plugin Catalog' 子串）
  const plugIdx = ctx.indexOf('\n# Plugin Catalog');
  const presetIdx = ctx.indexOf('\n# Model Preset Catalog');
  const endOf = (i) => {
    const nxt = [presetIdx, ctx.indexOf('\n任务：')].filter((x) => x > i).sort((a, b) => a - b)[0];
    return nxt > i ? nxt : ctx.length;
  };
  const plugSection = plugIdx >= 0 ? ctx.slice(plugIdx, endOf(plugIdx)).trim() : '';
  const presetSection = presetIdx >= 0 ? ctx.slice(presetIdx, endOf(presetIdx)).trim() : '';
  const pe = tokenEstimate(plugSection);
  const se = tokenEstimate(presetSection);
  console.log('\n── token 量化（E2E 实测）──');
  console.log(`plugin section : ${pe.chars} chars (cjk=${pe.cjk}, ascii=${pe.ascii}) ≈ ${pe.tokensEst} tokens`);
  console.log(`preset section : ${se.chars} chars (cjk=${se.cjk}, ascii=${se.ascii}) ≈ ${se.tokensEst} tokens`);
  console.log(`combined       : ${pe.chars + se.chars} chars ≈ ${pe.tokensEst + se.tokensEst} tokens`);
  writeFileSync('/tmp/dctx-token-evidence.json', JSON.stringify({ plugin: pe, preset: se, pluginSectionText: plugSection, presetSectionText: presetSection }, null, 2));
} else {
  console.log('— captured requests: ' + captured.length);
  for (const c of captured.slice(0, 3)) console.log('  last user: ' + JSON.stringify([...c.messages].reverse().find((m) => m.role === 'user')?.content).slice(0, 160));
}

await cleanup();
console.log(failed === 0 ? '\nALL PASS' : `\n${failed} CHECK(S) FAILED`);
process.exit(failed === 0 ? 0 : 1);
