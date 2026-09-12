#!/usr/bin/env node
// e2e-plugin-surface-live.mjs — plugins-live 批（2026-09-12）E2E：插件关闭/重开在
// **已开着的分发器会话**内的收敛（隔离实例 + 内嵌 OpenAI 兼容 stub LLM 捕获真实请求）。
//
// 被修缺陷（作者 2026-09-12 12:52 报障）：「我关闭了插件，任务分发器好像还是能看到」。
// 取证（~/.nebflow/docs/Nebflow/20260912_130117_plugin-face-forensics__chain-n-bb115537.md §B）：
// Plugin Catalog 只在 spawn 时进首条消息（ProjectActor.pluginCatalogText → L647/L691），
// 会话存活期内零刷新路径 ⇒ 已开的分发器会话永远看到 spawn 期快照。
//
// 本脚本的验收链（全真实：真分发器会话 + 真 REST revoke/approve + 真 LLM 请求体）：
//   A. 派 task-1 → 分发器 spawn（首条消息含 cap-a 目录行）
//   B. turn-1 请求在 stub 侧**挂住**（会话保持活跃）→ 期间 REST revoke cap-a +
//      注入 task-2（Nebula 再调 Task → 同一活跃会话注入，不重启不重开）
//   C. turn-2（task 注入触发的轮）请求必须带 plugin-surface 提醒：cap-a 标为已关闭、
//      **不再出现其能力描述**、并带当前权威目录 —— ①
//   D. turn-2 挂住 → 期间 REST approve cap-a + 注入 task-3
//   E. turn-3 请求的提醒里 cap-a 回归可用（含能力行）—— ②
//   F. turn-3 挂住 → 期间**不变更插件面**，只注入 task-4
//   G. turn-4 请求**零提醒**（无 plugin-surface、无任何 <system-reminder>）—— ③
//   附：读隔离实例 LlmLogWriter（logs/router）作请求层第二证据源；读会话文件证提醒已持久化。
//
// Run（worktree 根）：
//   node scripts/e2e-plugin-surface-live.mjs
// Env：GATEWAY_PORT/MOCK_PORT/OUT(证据目录)/NEBFLOW_HOME；CLEAN=1 收尾删除隔离 home（默认保留）。

import { spawn, execSync } from 'node:child_process';
import http from 'node:http';
import { readFileSync, writeFileSync, appendFileSync, mkdirSync, cpSync, existsSync, readdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { tmpdir } from 'node:os';
import { setTimeout as sleep } from 'node:timers/promises';
import { guardFixtureHome, safeRm } from './lib/delguard.mjs';

const REPO = dirname(dirname(fileURLToPath(import.meta.url)));
const GATEWAY_PORT = Number(process.env.GATEWAY_PORT || 8096);
const MOCK_PORT = Number(process.env.MOCK_PORT || 18996);
const BASE = `http://127.0.0.1:${GATEWAY_PORT}`;
const HOME = process.env.NEBFLOW_HOME || join(tmpdir(), `nb-pluglive-e2e-${Date.now()}`);
// 证据默认落**主仓** `.nebflow/evidence/`（worktree 内的 .nebflow 随 worktree 清理会丢；
// git-common-dir 的父目录 = 主仓根，worktree 内运行也指向主仓）。
const MAIN_REPO = dirname(execSync('git rev-parse --path-format=absolute --git-common-dir', { cwd: REPO }).toString().trim());
const OUT = process.env.OUT || join(MAIN_REPO, '.nebflow', 'evidence', '20260912_pluglive-live');
const DISPATCHER_AGENT_SRC = process.env.DISPATCHER_AGENT_SRC || join(REPO, 'src', 'main', 'resources', 'seed', 'agents', 'project-dispatcher');
guardFixtureHome(HOME, { label: 'e2e-plugin-surface-live' });
mkdirSync(OUT, { recursive: true });
const runLogPath = join(OUT, 'run.log');
writeFileSync(runLogPath, '');

const CAP_A_DESC = 'PLUGIN-CAP-A 能力描述句（关闭后必须从提醒里消失）';
const MARKER = 'Plugin surface changed';

let failed = 0;
const checks = [];
function check(name, ok, extra = '') {
  if (!ok) failed++;
  checks.push({ name, ok, extra });
  log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
}

// 逐行落盘（run.log）——工具超时/长跑也能留下现场（不只在收尾写证据）。
function log(line) {
  const s = `${new Date().toISOString()} ${line}`;
  console.log(s);
  try { appendFileSync(runLogPath, s + '\n'); } catch { /* best effort */ }
}

/** 有界等待：超时抛错（绝不无限挂起）——每个 phase 都有界，失败可诊断。 */
async function waitFor(pred, label, timeoutMs = 120000) {
  const t0 = Date.now();
  while (Date.now() - t0 < timeoutMs) {
    const v = pred();
    if (v) return v;
    await sleep(250);
  }
  throw new Error(`timeout after ${timeoutMs}ms waiting for ${label}`);
}

// ── 进程清理（trap cleanup EXIT 纪律）──
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
  await sleep(500);
  for (const port of [GATEWAY_PORT, MOCK_PORT]) {
    try {
      const pids = execSync(`lsof -tiTCP:${port} -sTCP:LISTEN 2>/dev/null`, { shell: '/bin/bash' }).toString().trim();
      if (pids) for (const pid of pids.split('\n').filter(Boolean)) {
        try { process.kill(Number(pid), 'SIGKILL'); } catch { /* gone */ }
      }
    } catch { /* no listener — good */ }
  }
  if (process.env.CLEAN === '1' || process.env.NB_CLEAN === '1') safeRm(HOME, { label: 'e2e-plugin-surface-live' });
}
for (const sig of ['SIGINT', 'SIGTERM', 'uncaughtException', 'unhandledRejection']) {
  process.on(sig, async (e) => { if (e) console.error(e); await cleanup(); process.exit(1); });
}

// ── stub LLM（OpenAI 兼容流式；捕获全部请求；按轮挂起/放行做时序编排）──
const captured = [];                       // { round|kind, messages, ts, reminders:[…] }
const dispatcherReqs = [];                 // dispatcher 会话请求（按到达序）
const gates = [];                          // 1-based：放行第 r 个 dispatcher 请求
const arrivals = [];                       // 1-based：第 r 个 dispatcher 请求已到达
function makeDeferred() {
  let resolve;
  const promise = new Promise((r) => { resolve = r; });
  return { promise, resolve };
}
for (let i = 0; i <= 6; i++) { gates[i] = makeDeferred(); arrivals[i] = makeDeferred(); }
const waitDispatcherReq = (r) => arrivals[r].promise;
const releaseGate = (r) => gates[r].resolve();
let stubRound = 0;

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
  return [chunk({ role: 'assistant', content: '' }), chunk({ content: 'ok' }), chunk({}, 'stop'), 'data: [DONE]\n\n'].join('');
}
const isDispatchCtx = (msgs) => {
  const s = JSON.stringify(msgs);
  return s.includes('你是项目') && s.includes('任务分发器');
};
function lastUserText(msgs) {
  const m = [...msgs].reverse().find((x) => x.role === 'user' && msgText(x));
  return msgText(m);
}
/** Turn-leading user text: walk from the tail, skip the request-only
  * `<system-reminder>` messages (time reminder is appended as the LAST user
  * message of a real-user turn), and return the first real text message. A
  * tool-continuation round has a text-less tail (ToolResult) → "" (so the marker
  * fires exactly once per turn instead of looping on the historic marker). */
function tailUserText(msgs) {
  for (let i = msgs.length - 1; i >= 0; i--) {
    const m = msgs[i];
    const t = msgText(m).trim();
    if (!t) return '';
    if (t.startsWith('<system-reminder>')) continue;
    return m.role === 'user' ? t : '';
  }
  return '';
}
function startMock() {
  const server = http.createServer((req, res) => {
    if (req.method === 'POST' && (req.url === '/v1/chat/completions' || req.url === '/chat/completions')) {
      // Buffer 拼接后一次解码：多字节 UTF-8 字符跨 TCP chunk 时逐块 toString 会
      // 产生 U+FFFD（会污染「请求字节面」对照读数——实测踩过）。
      const parts = [];
      req.on('data', (d) => parts.push(d));
      req.on('end', async () => {
        const raw = Buffer.concat(parts).toString('utf8');
        let body = {};
        try { body = JSON.parse(raw); } catch { /* tolerate */ }
        stubRound++;
        const msgs = body.messages || [];
        const entry = { round: stubRound, kind: isDispatchCtx(msgs) ? 'dispatcher' : 'other', messages: msgs, ts: Date.now() };
        captured.push(entry);
        let events;
        if (entry.kind === 'dispatcher') {
          const r = dispatcherReqs.push(entry);
          console.log(`[stub] dispatcher request #${r} (stub round ${stubRound})`);
          arrivals[r].resolve(entry);
          if (r <= 3) {
            // 挂起本请求 = 会话保持活跃（turn 未终结）→ 控制流在此窗口内改插件面 +
            // 注入下一件（注入件在 turn 边界起新 turn，且桥的 pendingInjected 保活）
            await gates[r].promise;
          }
          events = stop(body);
        } else {
          const last = tailUserText(msgs);
          const m = /NB_TASK=(\d)/.exec(last);
          if (m) {
            // R2「一个 Mail 统一」批后 Task 工具已退役：项目派活 = Mail(address=<project>)，
            // 内核仍是 ProjectActor.TriggerDispatcher。
            const args = JSON.stringify({ address: 'e2e-proj', message: `task-${m[1]}: 插件面热生效探针 #${m[1]}` });
            events = [
              chunk({ role: 'assistant', content: '' }),
              chunk({ tool_calls: [{ index: 0, id: `call_mail_${m[1]}`, type: 'function', function: { name: 'Mail', arguments: args } }] }),
              chunk({}, 'tool_calls'),
              'data: [DONE]\n\n',
            ].join('');
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

// ── fixture home ──
function buildFixture() {
  mkdirSync(HOME, { recursive: true });
  const ws = join(HOME, 'ws-e2e-proj');
  cpSync(DISPATCHER_AGENT_SRC, join(HOME, 'agents', 'project-dispatcher'), { recursive: true });
  const capA = join(HOME, 'plugins', 'cap-a');
  mkdirSync(join(capA, 'skills', 'probe'), { recursive: true });
  writeFileSync(join(capA, 'plugin.json'), JSON.stringify({
    $schema: 'https://agent-plugins.org/schemas/1.0.0/plugin.schema.json', name: 'cap-a', version: '1.0.0',
    description: CAP_A_DESC,
  }, null, 2));
  writeFileSync(join(capA, 'skills', 'probe', 'SKILL.md'), '---\nname: probe\ndescription: probe skill\n---\n# probe\nbody');
  const beta = join(HOME, 'plugins', 'beta-untrusted');
  mkdirSync(join(beta, 'skills', 'never'), { recursive: true });
  writeFileSync(join(beta, 'plugin.json'), JSON.stringify({
    $schema: 'https://agent-plugins.org/schemas/1.0.0/plugin.schema.json', name: 'beta-untrusted', version: '1.0.0',
    description: '未信任插件（不应出现）',
  }, null, 2));
  writeFileSync(join(beta, 'skills', 'never', 'SKILL.md'), '---\nname: never\ndescription: never skill\n---\n# never\nbody');
  writeFileSync(join(HOME, 'model-presets.json'), JSON.stringify({
    defaultPreset: 'general',
    presets: {
      general: { name: 'general', description: '', preferred: 'mock/mock-1', fallbacks: [] },
      'deep-analyze': { name: 'deep-analyze', description: '深度分析场景：调研/阅卷/方案设计节点适用', preferred: 'mock/mock-1', fallbacks: [] },
    },
  }));
  writeFileSync(join(HOME, 'nebflow.json'), JSON.stringify({
    llm: { providers: { mock: { baseUrl: `http://127.0.0.1:${MOCK_PORT}/v1/`, apiKey: 'sk-e2e-stub', protocol: 'openai', models: [{ id: 'mock-1', maxTokens: 8192, contextWindow: 128000 }] } } },
    plugins: { enabled: true },
  }));
  mkdirSync(ws, { recursive: true });
  mkdirSync(join(HOME, 'projects', 'e2e-proj'), { recursive: true });
  writeFileSync(join(HOME, 'projects', 'e2e-proj', 'project.json'), JSON.stringify({
    name: 'e2e-proj', workspace: ws, agentFile: join(ws, 'AGENTS.md'), createdAt: Date.now(),
  }));
  writeFileSync(join(ws, 'AGENTS.md'), '# e2e-proj\n\nE2E fixture workspace (plugin-surface-live).\n');
}

// ── 隔离实例（AGENTS.md 隔离配方：--home/--port + NEBFLOW_GATEWAY_PORT env 冗余）──
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
    if (/Compiling|Done|running|Running|error|Exception|listen|Listen|started|Started|Gateway|port|plugin-surface|Seed|dispatcher/i.test(s)) {
      console.log(`${tag} ${s.trim().slice(0, 160)}`);
    }
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

// ── 证据辅助 ──
const SR = /<system-reminder>[\s\S]*?<\/system-reminder>/g;
function readings(entry) {
  const texts = entry.messages.map((m) => msgText(m));
  const srMsgs = entry.messages
    .map((m, i) => ({ i, role: m.role, text: msgText(m), blocks: (msgText(m).match(SR) || []) }))
    .filter((x) => x.blocks.length > 0);
  const joined = texts.join('\n');
  return {
    kind: entry.kind,
    messageCount: entry.messages.length,
    totalChars: texts.reduce((a, t) => a + t.length, 0),
    systemReminderMessages: srMsgs.length,
    systemReminderBlocks: (joined.match(SR) || []).length,
    hasPluginSurfaceReminder: joined.includes(MARKER),
    reminderTexts: srMsgs.flatMap((x) => x.blocks),
  };
}

// ══ 主链 ══
const H8080_BEFORE = hostListeners8080();
log(`[guard] 8080 listeners before: ${H8080_BEFORE || '(none)'}`);
log(`[out] evidence dir: ${OUT}`);
log(`[guard] isolated home: ${HOME}`);

// ── 隔离实例 REST / Nebula turn 辅助（只打隔离端口 BASE；宿主 8080 全程只读 GET）──
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
let NEBULA_SID = null;
async function nebulaTurn(n) {
  if (!NEBULA_SID) {
    const sess = await api('/sessions', 'POST', { name: 'e2e-pluglive', agentName: 'Nebula' });
    NEBULA_SID = sess.json?.id ?? sess.json?.sessionId;
    check('create Nebula session', sess.status === 200 && !!NEBULA_SID, JSON.stringify(sess.json || {}).slice(0, 120));
  }
  const r = await api(`/sessions/${NEBULA_SID}/turn`, 'POST', {
    content: `NB_TASK=${n}：立即调用 Mail 工具（address="e2e-proj"，message="task-${n}: 插件面热生效探针 #${n}"），除此之外什么都不要做。`,
    timeoutSec: 120,
  });
  check(`Nebula turn #${n} completes (Task fired inside)`, r.status === 200, `status=${r.status} body=${r.text.slice(0, 160)}`);
  return r;
}

function dumpPartial() {
  try {
    writeFileSync(join(OUT, '01-stub-captured-dispatcher-requests.json'), JSON.stringify(dispatcherReqs, null, 2));
    writeFileSync(join(OUT, '04-request-readings.json'), JSON.stringify({
      gatewayPort: GATEWAY_PORT, mockPort: MOCK_PORT, home: HOME,
      dispatcherRequestCount: dispatcherReqs.length,
      dispatcherRequests: dispatcherReqs.map((e, i) => ({ index: i + 1, ...readings(e) })),
      allRounds: captured.map((c) => ({ round: c.round, kind: c.kind, messageCount: c.messages.length, lastUser: lastUserText(c.messages).slice(0, 200) })),
      checks,
    }, null, 2));
    writeFileSync(join(OUT, '08-gateway.log'), gatewayLog);
  } catch (e) { log(`[dump] failed: ${e.message}`); }
}

try {
  mockServer = await startMock();
  log(`[phase] stub LLM up on :${MOCK_PORT}`);
  buildFixture();
  log(`[phase] fixture built at ${HOME}`);
  startGateway();
  if (!(await waitGateway(240000))) throw new Error('gateway never came up');
  check('isolated gateway up', true, BASE);

  globalThis.__TOKEN = TOKEN = JSON.parse(readFileSync(join(HOME, 'auth.json'), 'utf8'));

  // A. 审批 cap-a → 派 task-1 → 分发器 spawn
  const appr = await api('/plugins/cap-a/approve', 'POST');
  check('approve cap-a (isolated instance only)', appr.status === 200, JSON.stringify(appr.json || appr.text).slice(0, 140));
  await nebulaTurn(1);
  await waitFor(() => dispatcherReqs[0], 'dispatcher spawn request #1', 150000);
  const req1 = dispatcherReqs[0];
  const r1 = readings(req1);
  check('A1 dispatcher spawned, request #1 captured', !!req1);
  check('A2 spawn catalog carries cap-a capability line', JSON.stringify(req1.messages).includes(`- cap-a: ${CAP_A_DESC}`));
  check('A3 request #1 has NO plugin-surface reminder (lifecycle baseline, zero noise)', !r1.hasPluginSurfaceReminder);

  // B. 关闭 cap-a（不重启不重开会话）+ 把 task-2 注入同一活跃会话
  const rev = await api('/plugins/cap-a/revoke', 'POST');
  check('B1 revoke cap-a on the LIVE instance', rev.status === 200, JSON.stringify(rev.json || rev.text).slice(0, 140));
  check('B2 dispatcher session still alive (no re-spawn)', dispatcherReqs.length === 1, `dispatcher requests so far=${dispatcherReqs.length}`);
  await nebulaTurn(2);
  releaseGate(1);
  dumpPartial();

  // C. turn-2（task 注入触发的轮）：提醒必须声明 cap-a 已关闭 + 不再出现其能力描述
  await waitFor(() => dispatcherReqs[1], 'dispatcher request #2 (post-revoke turn)', 120000);
  const req2 = dispatcherReqs[1];
  const r2 = readings(req2);
  const rem2 = r2.reminderTexts.filter((t) => t.includes(MARKER)).join('\n\n');
  writeFileSync(join(OUT, '02-reminder-after-revoke.txt'), rem2);
  dumpPartial();
  check('C1 request #2 (same session) carries the plugin-surface reminder', r2.hasPluginSurfaceReminder);
  check('C2 reminder names cap-a as closed', rem2.includes('已关闭') && rem2.includes('- cap-a'), rem2.slice(0, 160));
  check('C3 reminder does NOT re-advertise the closed plugin capability (VOID clause)', !rem2.includes(CAP_A_DESC));
  check('C4 reminder carries the authoritative current catalog (without cap-a)', rem2.includes('当前插件面（权威目录）') && !rem2.includes(`- cap-a: ${CAP_A_DESC}`));
  check('C5 reminder declares authority over the first message catalog', rem2.includes('首条消息') && rem2.includes('作废'));
  check('C6 no new dispatcher session (still 2 requests, same session)', dispatcherReqs.length === 2);

  // D. 重新 approve + 注入 task-3
  const re = await api('/plugins/cap-a/approve', 'POST');
  check('D1 re-approve cap-a', re.status === 200, JSON.stringify(re.json || re.text).slice(0, 140));
  await nebulaTurn(3);
  releaseGate(2);

  // E. turn-3：能力行回归
  await waitFor(() => dispatcherReqs[2], 'dispatcher request #3 (post-approve turn)', 120000);
  const req3 = dispatcherReqs[2];
  const r3 = readings(req3);
  const rem3 = r3.reminderTexts.filter((t) => t.includes(MARKER)).join('\n\n');
  writeFileSync(join(OUT, '03-reminder-after-approve.txt'), rem3);
  dumpPartial();
  check('E1 request #3 carries the plugin-surface reminder again', r3.hasPluginSurfaceReminder);
  check('E2 reminder reports cap-a available again with its capability line', rem3.includes('已批准 / 重新可用') && rem3.includes(`- cap-a: ${CAP_A_DESC}`));
  check('E3 still the same session (3 requests, no re-spawn)', dispatcherReqs.length === 3);

  // F. 无变更 + 注入 task-4
  await nebulaTurn(4);
  releaseGate(3);

  // G. turn-4：零噪音（无变更 ⇒ 不产生任何新 reminder）
  await waitFor(() => dispatcherReqs[3], 'dispatcher request #4 (no-change turn)', 120000);
  const req4 = dispatcherReqs[3];
  const r4 = readings(req4);
  const last4 = JSON.stringify(req4.messages[req4.messages.length - 1]);
  // 提醒是持久化消息（在**历史里**跨轮承载，非每轮重注入）：#3 的提醒消息在 #4 里
  // 逐字复用，且**没有新增第二条**——这正是零噪音的可反向证伪断言。
  check('G1 no NEW reminder injected on the no-change turn (reminder count unchanged #3→#4)',
    r4.systemReminderMessages === r3.systemReminderMessages,
    `#3 reminders=${r3.systemReminderMessages} | #4 reminders=${r4.systemReminderMessages}`);
  check('G2 the turn-4 tail (fresh task injection) carries NO reminder',
    !last4.includes('<system-reminder>'), last4.slice(0, 120));
  check('G3 byte-level reuse: the whole pre-turn context of #4 is identical to #3 (zero reminder bytes added)',
    JSON.stringify(req4.messages.slice(0, r3.messageCount)) === JSON.stringify(req3.messages),
    `#3 msgs=${r3.messageCount} chars=${r3.totalChars} | #4 msgs=${r4.messageCount} chars=${r4.totalChars}`);
  check('G4 reminder carried across turns is byte-identical (same persisted message, not a re-render)',
    JSON.stringify(r4.reminderTexts) === JSON.stringify(r3.reminderTexts));
  check('G5 exactly ONE reminder copy survives two changes (prune keeps the newest authoritative value)',
    r4.systemReminderMessages === 1, `count=${r4.systemReminderMessages}`);

  // ── 请求层读数（stub 捕获 = 真实请求体；LlmLogWriter = 实例侧落盘）──
  dumpPartial();
  writeFileSync(join(OUT, '04-request-readings.json'), JSON.stringify({
    gatewayPort: GATEWAY_PORT, mockPort: MOCK_PORT, home: HOME,
    checks,
    dispatcherRequests: dispatcherReqs.map((e, i) => ({ index: i + 1, ...readings(e) })),
    otherRequestCount: captured.filter((c) => c.kind !== 'dispatcher').length,
  }, null, 2));

  // LlmLogWriter（实例侧）：logs/router 的 request 行 + objects 全文
  try {
    const routerDir = join(HOME, 'logs', 'router');
    if (existsSync(routerDir)) {
      const files = readdirSync(routerDir).filter((f) => f.endsWith('_full.jsonl'));
      let lines = [];
      for (const f of files) lines = lines.concat(readFileSync(join(routerDir, f), 'utf8').split('\n').filter(Boolean));
      const objs = join(routerDir, 'objects');
      const reminderObjects = [];
      if (existsSync(objs)) {
        for (const f of readdirSync(objs)) {
          const txt = readFileSync(join(objs, f), 'utf8');
          if (txt.includes(MARKER)) reminderObjects.push({ file: f, text: txt });
        }
      }
      writeFileSync(join(OUT, '05-llmwriter-request-lines.json'), JSON.stringify(lines.map((l) => { try { return JSON.parse(l); } catch { return l; } }), null, 2));
      writeFileSync(join(OUT, '06-llmwriter-reminder-objects.json'), JSON.stringify(reminderObjects, null, 2));
      check('H1 LlmLogWriter captured the reminder in an instance-side request object', reminderObjects.length >= 2, `objects with marker=${reminderObjects.length}`);
    } else {
      check('H1 LlmLogWriter captured the reminder in an instance-side request object', false, 'router dir missing');
    }
  } catch (e) {
    check('H1 LlmLogWriter captured the reminder in an instance-side request object', false, `err=${e.message}`);
  }

  // 会话文件（best-effort 读数；子代理会话是否落盘由 SessionStore 决定）
  try {
    await sleep(4000);   // SessionStore 的 messages 落盘是防抖的——等一拍再读
    const sessDir = join(HOME, 'sessions');
    const files = readdirSync(sessDir).filter((f) => f.endsWith('.json'));
    const onDisk = [];
    for (const f of files) {
      const txt = readFileSync(join(sessDir, f), 'utf8');
      if (txt.includes(MARKER)) onDisk.push({ file: f, reminderCount: (txt.match(/Plugin surface changed/g) || []).length, bytes: txt.length });
    }
    writeFileSync(join(OUT, '07-session-files-with-reminder.json'), JSON.stringify({ sessionFiles: files, onDisk }, null, 2));
    log(`[reading] session files on disk with reminder: ${JSON.stringify(onDisk)}`);
  } catch (e) {
    log(`[reading] session-file probe failed: ${e.message}`);
  }
  // 跨轮持久性（本批语义 = 会话历史内持久 + request 内在场，不依赖落盘）：
  // #4 的请求里带着 #3 注入的那条提醒 → 关闭/重开的权威值在后续每一轮都在场。
  check('H2 the reminder is PERSISTED into session history (carried into the next turn\'s request)',
    r4.hasPluginSurfaceReminder && JSON.stringify(r4.reminderTexts) === JSON.stringify(r3.reminderTexts));

  writeFileSync(join(OUT, '08-gateway.log'), gatewayLog);
  const reminderLogLines = (gatewayLog.match(/\[plugin-surface\].*/g) || []);
  writeFileSync(join(OUT, '09-plugin-surface-log-lines.txt'), reminderLogLines.join('\n'));
  check('H3 reminder log lines emitted (logAndReturn channel)', reminderLogLines.length >= 2, `lines=${reminderLogLines.length}`);
} catch (e) {
  log(`FATAL ${e && e.stack ? e.stack : e}`);
  check(`run completed without fatal error`, false, String(e && e.message || e));
  dumpPartial();
} finally {
  const H8080_AFTER = hostListeners8080();
  check('Z1 host 8080 listener set unchanged', H8080_BEFORE === H8080_AFTER, `${H8080_BEFORE} → ${H8080_AFTER}`);
  await cleanup();
  writeFileSync(join(OUT, '00-summary.txt'),
    `${checks.map((c) => `${c.ok ? 'PASS' : 'FAIL'}  ${c.name}${c.extra ? '  — ' + c.extra : ''}`).join('\n')}\n\n${failed === 0 ? 'ALL PASS' : failed + ' CHECK(S) FAILED'}\n`);
  log(failed === 0 ? 'ALL PASS' : `${failed} CHECK(S) FAILED`);
  process.exit(failed === 0 ? 0 : 1);
}

