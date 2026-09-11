#!/usr/bin/env node
// e2e-askuser-source-label.mjs — D6 批 F1+F2 E2E（2026-09-08）。
//
// 验收链（真实全环，模式复用 e2e-project-memory.mjs）：隔离 NEBFLOW_HOME +
// 隔离实例（assembly jar + NEBFLOW_GATEWAY_PORT + --port/--home/--no-browser，
// 绝非 8080 宿主，禁 sbt run）+ OpenAI 兼容 stub LLM（脚本化工具调用）。
//
// fixture：项目 qa-f1f2（workspace + project.json，启动 mountAll 自动挂载）；
// agents：project-dispatcher 拷宿主真身、general 拷 seed 资源（D1 八件面）。
//
// 剧本：
//   Nebula turn「F1F2 dispatch」→ Mail→qa-f1f2 → 分发器 round1 NodeEdit 建
//   probe-a（创建即运行）、round2 NodeEdit 建 probe-b → 两节点首请求各回
//   AskUserQuestion 调用（问题带 F1F2-PROBE-A/B 标记）→ 两张提问卡挂起。
//   Nebula turn「F1F2 selfask」→ Nebula 自问 AskUserQuestion（badge 不回归
//   对照：Nebula 卡无 badge）。
//
// 断言（对应任务书验收口径）：
//   F1① 两节点卡 badge「qa-f1f2 · probe-a / probe-b」+ 明/暗主题截图
//   F1② Nebula 自问卡无 badge（agentName=Nebula 抑制逻辑不回归）
//   F1③ 刷新后 replayed 帧携带 project/nodeName（WS 帧嗅探 + DOM 重建断言）
//   F1④ 两节点并发两卡 badge 各自正确（同 F1① 并发形态）
//   F2① 两卡并发时常驻条两行 + header badge=2（DOM 断言）
//   F2② 回答（卡片确认）/取消（卡片取消）/直答（chat-input）三路条目摘除
//       各验一；来源死亡路 = 已知边界（引擎 askUserClosed 触发归批 E2，
//       前端处理已就位——本脚本以帧嗅探证明前端 handler 注册存在即可）
//   F2③ 刷新重连后 ListPendingAsks 对账：常驻条/badge 重建正确
//   F2④ console 零错误
//   引擎留痕：workspace .nebflow/flow-map-events.jsonl 含 node-ask 事件×2
//
// Run（worktree 根，jar 已构建）：node scripts/e2e-askuser-source-label.mjs
// Env：fixture home 默认**不删**（要清理设 CLEAN=1 / NB_CLEAN=1；NB_DRY_RUN=1 只跑删除守卫断言）；
//   GATEWAY_PORT=8098 MOCK_PORT=18998；
//   JAR 默认 target/scala-3.5.2/nebflow-assembly-*.jar（取最新）。

import { spawn, execSync } from 'node:child_process';
import http from 'node:http';
import { readFileSync, mkdirSync, writeFileSync, cpSync, rmSync, existsSync, readdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { homedir, tmpdir } from 'node:os';
import { setTimeout as sleep } from 'node:timers/promises';
import { chromium } from '/opt/homebrew/lib/node_modules/playwright/index.mjs';
import { guardFixtureHome, safeRm, assertCleanable } from './lib/delguard.mjs';

const REPO = dirname(dirname(fileURLToPath(import.meta.url)));
const GATEWAY_PORT = Number(process.env.GATEWAY_PORT || 8098);
const MOCK_PORT = Number(process.env.MOCK_PORT || 18998);
const BASE = `http://127.0.0.1:${GATEWAY_PORT}`;
const HOME = process.env.NEBFLOW_HOME || join(tmpdir(), 'qa-f1f2-askuser');
// R4 删除守卫：解析后的真实绝对路径必须落 tmpdir 之下（≠ tmpdir 自身）——早期 fail-fast，先于任何 spawn。
// NB_DRY_RUN=1：只跑断言、不删除、不 spawn（下游负控入口）。
guardFixtureHome(HOME, { label: 'e2e-askuser-source-label' });
const PROJECT = 'qa-f1f2';
const WS_DIR = join(HOME, `ws-${PROJECT}`);
const DISPATCHER_AGENT_SRC = process.env.DISPATCHER_AGENT_SRC || join(homedir(), '.nebflow', 'agents', 'project-dispatcher');
const GENERAL_AGENT_SRC = join(REPO, 'src/main/resources/seed/agents/general');
const SHOTS = process.env.QA_SHOTS || join(homedir(), '.nebflow/docs/Nebflow/assets/20260908_f1f2-askuser');

const QA = '方案抉择甲：选用晨雾配色还是暮蓝配色？'; // node A question（<40 字符，不截断对照）
const QB = '方案抉择乙：是否启用实验性渲染开关，并同步更新相关文档、测试用例、发布说明与官网特性矩阵？'; // node B question（>40 字符 → 截断验证）
const QN = 'Nebula 自问：确认继续吗？';
const expectedSummary = (q) => (q.length > 40 ? q.slice(0, 37) + '...' : q);

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
}
for (const sig of ['SIGINT', 'SIGTERM', 'uncaughtException', 'unhandledRejection']) {
  process.on(sig, async (e) => { if (e) console.error(e); await cleanup(); process.exit(1); });
}

// ── pre-flight：目标端口必须空；8080 宿主集合快照（post-flight 双断言用）──
{
  const busy = execSync(`lsof -nP -iTCP:${GATEWAY_PORT} -sTCP:LISTEN 2>/dev/null || true`, { shell: '/bin/bash' }).toString().trim();
  if (busy) { console.error(`port ${GATEWAY_PORT} occupied — abort`); process.exit(1); }
  const busyMock = execSync(`lsof -nP -iTCP:${MOCK_PORT} -sTCP:LISTEN 2>/dev/null || true`, { shell: '/bin/bash' }).toString().trim();
  if (busyMock) { console.error(`port ${MOCK_PORT} occupied — abort`); process.exit(1); }
}
const H8080_BEFORE = execSync('lsof -nP -iTCP:8080 -sTCP:LISTEN -t 2>/dev/null | sort | tr "\\n" " "', { shell: '/bin/bash' }).toString().trim();

// ── stub LLM（OpenAI 兼容流式；脚本化工具调用）──
const captured = [];
let nodeEditCount = 0;
// Fire the Nebula→Task dispatch exactly once. Without this guard the stub
// loops forever: the dispatcher's completion delivery back to Nebula contains
// the task text ("[Dispatcher 'qa-f1f2' · task: F1F2 dispatch probe…]"), which
// re-matches the 'F1F2 dispatch' branch and re-triggers Task → dispatcher →
// delivery → … (observed R2 run 21:26: the loop kept Nebula perpetually busy,
// starving the later selfask injection and the chat-input passthrough probe).
let taskFired = false;
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
function askCall(id, question, marker) {
  return done([
    chunk({ role: 'assistant', content: '' }),
    chunk(toolCall(id, 'AskUserQuestion', {
      questions: [{ question, options: [{ label: `${marker} 选项一` }, { label: `${marker} 选项二` }], allowOther: true }],
    })),
    chunk({}, 'tool_calls'),
  ]);
}
function classify(body) {
  const msgs = body.messages || [];
  const s = JSON.stringify(msgs);
  if (s.includes('你是项目') && s.includes('任务分发器')) return 'dispatcher';
  if (s.includes('F1F2-PROBE-A')) return 'node-A';
  if (s.includes('F1F2-PROBE-B')) return 'node-B';
  const last = msgs[msgs.length - 1];
  if (last?.role === 'tool') return 'tool-followup';
  return 'nebula';
}
function streamFor(body) {
  const tag = classify(body);
  const msgs = body.messages || [];
  const last = msgs[msgs.length - 1];
  const lastUser = [...msgs].reverse().find((m) => m.role === 'user');
  const turn = lastUser ? msgText(lastUser) : '';

  if (tag === 'dispatcher') {
    // round1 → NodeEdit probe-a；round2（NodeEdit A 工具结果回填）→ NodeEdit
    // probe-b；之后终答。创建即运行 → 节点首请求带任务标记到 stub。
    if (nodeEditCount === 0) {
      nodeEditCount = 1;
      return done([
        chunk({ role: 'assistant', content: '' }),
        chunk(toolCall('call_nodeedit_a', 'NodeEdit', {
          project: PROJECT, nodename: 'probe-a', description: 'F1F2 探针节点甲（AskUser 来源标注验收）',
          task: 'F1F2-PROBE-A：立即调用 AskUserQuestion 提问（内容任意），然后等待回答。', out: 'Nebula',
        })),
        chunk({}, 'tool_calls'),
      ]);
    }
    if (nodeEditCount === 1) {
      nodeEditCount = 2;
      return done([
        chunk({ role: 'assistant', content: '' }),
        chunk(toolCall('call_nodeedit_b', 'NodeEdit', {
          project: PROJECT, nodename: 'probe-b', description: 'F1F2 探针节点乙（溢出聚合验收）',
          task: 'F1F2-PROBE-B：立即调用 AskUserQuestion 提问（内容任意），然后等待回答。', out: 'Nebula',
        })),
        chunk({}, 'tool_calls'),
      ]);
    }
    return finalOk();
  }
  if (tag === 'node-A') return last?.role === 'tool' ? finalOk() : askCall('call_ask_a', QA, '甲');
  if (tag === 'node-B') return last?.role === 'tool' ? finalOk() : askCall('call_ask_b', QB, '乙');
  if (tag === 'tool-followup') return finalOk();
  // Nebula 会话轮次
  if (turn.includes('F1F2 selfask')) {
    return done([
      chunk({ role: 'assistant', content: '' }),
      chunk(toolCall('call_ask_nebula', 'AskUserQuestion', {
        questions: [{ question: QN, options: [{ label: '继续' }, { label: '停止' }], allowOther: false }],
      })),
      chunk({}, 'tool_calls'),
    ]);
  }
  if (turn.includes('F1F2 dispatch')) {
    // Nebula 面 Mail 已退役（2026-09-06 工具面裁撤批）——项目触发走 Task 工具
    // （TaskTool.scala: TriggerDispatcher 同内核）。一次性（taskFired 闸，
    // 防分发器投递回环再触发）。
    if (taskFired) return finalOk();
    taskFired = true;
    return done([
      chunk({ role: 'assistant', content: '' }),
      chunk(toolCall('call_stub_task', 'Task', { project: PROJECT, task: 'F1F2 dispatch probe：建两个 probe 节点（NodeEdit 剧本驱动）。' })),
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
        const msgs = body.messages || [];
        const last = msgs[msgs.length - 1];
        captured.push({ ts: Date.now(), tag: classify(body), lastRole: last?.role, lastText: msgText(last || {}).slice(0, 300) });
        res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-cache', connection: 'keep-alive' });
        res.end(streamFor(body));
      });
      return;
    }
    res.writeHead(404).end();
  });
  return new Promise((resolve) => server.listen(MOCK_PORT, '127.0.0.1', () => resolve(server)));
}

// ── fixture home ──
function buildFixture() {
  // R4：删除点二次断言（本处是 fixture 重建的幂等重置，非数据清理 → 不受 CLEAN 开关约束，但仍须过断言）
  assertCleanable(HOME, { label: 'e2e-askuser-source-label/buildFixture' });
  rmSync(HOME, { recursive: true, force: true });
  mkdirSync(HOME, { recursive: true });
  cpSync(DISPATCHER_AGENT_SRC, join(HOME, 'agents', 'project-dispatcher'), { recursive: true });
  cpSync(GENERAL_AGENT_SRC, join(HOME, 'agents', 'general'), { recursive: true });
  writeFileSync(join(HOME, 'nebflow.json'), JSON.stringify({
    llm: { providers: { mock: { baseUrl: `http://127.0.0.1:${MOCK_PORT}/v1/`, apiKey: 'sk-e2e-stub', protocol: 'openai', models: [{ id: 'mock-1', maxTokens: 8192, contextWindow: 128000 }] } } },
  }));
  mkdirSync(join(WS_DIR, '.nebflow'), { recursive: true });
  mkdirSync(join(HOME, 'projects', PROJECT), { recursive: true });
  writeFileSync(join(HOME, 'projects', PROJECT, 'project.json'), JSON.stringify({
    name: PROJECT, workspace: WS_DIR, agentFile: join(WS_DIR, 'AGENTS.md'), createdAt: Date.now(),
  }));
  writeFileSync(join(WS_DIR, 'AGENTS.md'), `# ${PROJECT}\n\nF1F2 E2E fixture workspace.\n`);
  // onboarding 预跳过（msg-search QA 经验：异步遮罩拦截指针事件）
  writeFileSync(join(HOME, 'onboarding.json'), JSON.stringify({ state: 'skipped' }));
}

// ── 隔离实例（assembly jar 配方；NEBFLOW_GATEWAY_PORT env 为主保险）──
function findJar() {
  const dir = join(REPO, 'target/scala-3.5.2');
  const jars = readdirSync(dir).filter((f) => f.startsWith('nebflow-assembly-') && f.endsWith('.jar'));
  if (!jars.length) throw new Error('assembly jar not found — run sbt assembly first');
  return join(dir, jars.sort().pop());
}
function startGateway() {
  const jar = findJar();
  console.log(`[phase] jar: ${jar}`);
  const child = spawn('java', [
    '--add-opens', 'java.base/java.lang=ALL-UNNAMED',
    '-cp', jar, 'nebflow.Main',
    '--home', HOME, '--port', String(GATEWAY_PORT), '--no-browser', 'start',
  ], {
    detached: true, stdio: ['ignore', 'pipe', 'pipe'],
    env: { ...process.env, NEBFLOW_GATEWAY_PORT: String(GATEWAY_PORT) },
  });
  children.push(child);
  const log = (d) => {
    const s = String(d).trim();
    if (/error|Exception|listen|Listen|started|Started|Gateway|port|AskUser|node-ask/i.test(s)) console.log(`[gw] ${s.slice(0, 200)}`);
  };
  child.stdout.on('data', log);
  child.stderr.on('data', log);
  return child;
}
async function waitGateway(timeoutMs = 120000) {
  const t0 = Date.now();
  while (Date.now() - t0 < timeoutMs) {
    try {
      const r = await fetch(BASE);
      if (r.status < 500) { console.log(`[poll] gateway up after ${Math.round((Date.now() - t0) / 1000)}s`); return true; }
    } catch { /* not yet */ }
    await sleep(1500);
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
// post-flight ①：8080 宿主集合与启动前一致
{
  const after = execSync('lsof -nP -iTCP:8080 -sTCP:LISTEN -t 2>/dev/null | sort | tr "\\n" " "', { shell: '/bin/bash' }).toString().trim();
  check('host 8080 listener set unchanged', after === H8080_BEFORE, `before=[${H8080_BEFORE}] after=[${after}]`);
}

const TOKEN = JSON.parse(readFileSync(join(HOME, 'auth.json'), 'utf8'));
async function api(path, method = 'GET', body) {
  const res = await fetch(`${BASE}/api${path}`, {
    method,
    headers: { authorization: `Bearer ${TOKEN}`, 'content-type': 'application/json' },
    body: body ? JSON.stringify(body) : undefined,
  });
  return { status: res.status, json: await res.json().catch(() => null) };
}

// 顶层 Nebula 根会话 = 首次启动自动创建的那个（boot 挂载 rootSessionId 的
// 来源；节点提问卡渲染落点）。不要另建会话——新建会话不是节点卡的投递目标。
const sessList = await api('/sessions');
const nebulaSessions = (sessList.json?.sessions || sessList.json || []).filter((s) => s.agentName === 'Nebula');
const sid = nebulaSessions[0]?.id;
check('root Nebula session found (boot-created)', sessList.status === 200 && !!sid, JSON.stringify(nebulaSessions[0] || sessList.json).slice(0, 140));

// dispatch turn：Task→qa-f1f2（同步等回合完成——Task 立即返回）
{
  const t = await api(`/sessions/${sid}/turn`, 'POST', {
    content: 'F1F2 dispatch：立即调用 Task 工具，project=qa-f1f2，task 任意。除此之外什么都不要做。',
    timeoutSec: 120,
  });
  check('Nebula dispatch turn completes (Task fired)', t.status === 200, `status=${t.status}`);
}

// 等两节点 AskUserQuestion 请求到 stub（节点提问后轮次挂起，无后续请求）
{
  const t0 = Date.now();
  let a = null, b = null;
  while (Date.now() - t0 < 120000) {
    a = captured.find((c) => c.tag === 'node-A');
    b = captured.find((c) => c.tag === 'node-B');
    if (a && b) break;
    await sleep(1500);
  }
  check('node-A ask request captured', !!a, `captured=${captured.map((c) => `${c.tag}[${c.lastRole}:${(c.lastText || '').slice(0, 80)}]`).join(' | ')}`);
  check('node-B ask request captured', !!b);
}
if (process.env.DEBUG_STOP) {
  console.log('[debug-stop] captured:', JSON.stringify(captured, null, 1));
  await cleanup();
  process.exit(1);
}

// ── Playwright ──
mkdirSync(SHOTS, { recursive: true });
async function openApp(colorScheme) {
  const browser = await chromium.launch();
  const context = await browser.newContext({
    baseURL: BASE, colorScheme, viewport: { width: 1440, height: 900 },
  });
  await context.addInitScript((tok) => {
    localStorage.setItem('nebflow_token', tok);
    // WS 帧嗅探：askUser 家族帧全量落 window.__askFrames（replay 保真断言用）
    window.__askFrames = [];
    const OrigWS = window.WebSocket;
    window.WebSocket = class extends OrigWS {
      constructor(...args) {
        super(...args);
        this.addEventListener('message', (ev) => {
          try {
            const msg = JSON.parse(ev.data);
            if (msg && ['askUser', 'askUserAnswered', 'askUserClosed'].includes(msg.type)) window.__askFrames.push(msg);
          } catch { /* non-JSON frame */ }
        });
      }
    };
  }, TOKEN);
  const page = await context.newPage();
  const consoleErrors = [];
  page.on('pageerror', (e) => consoleErrors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(`console.error: ${m.text()} ${(m.location()?.url || '')}`); });
  page.on('requestfailed', (r) => consoleErrors.push(`requestfailed: ${r.url()} ${r.failure()?.errorText || ''}`));
  page.on('response', (r) => { if (r.status() >= 400) consoleErrors.push(`http ${r.status()}: ${r.url()}`); });
  await page.goto('/', { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('#search-btn', { state: 'visible', timeout: 30_000 });
  const obAppeared = await page.waitForSelector('.onboarding-overlay', { state: 'attached', timeout: 4_000 }).then(() => true).catch(() => false);
  if (obAppeared) {
    const skip = await page.$('#ob-skip');
    if (skip) await skip.click(); else await page.click('#ob-no');
    await page.waitForSelector('.onboarding-overlay', { state: 'detached', timeout: 5_000 }).catch(() => {});
  }
  await page.waitForTimeout(600);
  return { browser, context, page, consoleErrors };
}

const light = await openApp('light');
const { page } = light;
// F2④ console-error 断言的基线噪音白名单（两者均由既有设计在 fresh fixture
// home 下必然触发）：
//   ① GET /api/nf-authcheck — ws.js probeCookieAuth 的当前靶点
//      （2026-09-11 C 批 R9 = O-A 迁移）。旧条目是「裸 GET 本地文件端点的
//      400 = cookie auth works」：票据腿批把那个端点改为票据-only 后，无票
//      请求恒 401，那条 400 语义已不存在，故白名单条目随靶点一起改写。
//      两种形态都要放行：403 **响应**（cookie 被禁 → 走 ?token= 回退，是
//      设计信号）与 **请求被 abort**（probeCookieAuth 自带
//      `AbortSignal.timeout(3000)`，页面导航/超时即 ERR_ABORTED，同样 by
//      design；本轮实跑抓到的就是后者）。
//   ② GET /api/canvas-tabs 404 —— 契约形态：无存档 → NotFound by design
//      （RestApiRoutes.scala:251-254「GET → 200 或 404（无存档）」）。
// 白名单只放这两个靶点的精确模式；任何其他 console 错误照旧判 FAIL。
const BASELINE_NOISE = [
  /http 403: .*\/api\/nf-authcheck/,
  /console\.error: .*\/api\/nf-authcheck/,
  /requestfailed: .*\/api\/nf-authcheck/,
  /http 404: .*\/api\/canvas-tabs/,
  /console\.error: .*\/api\/canvas-tabs/,
];
const realErrors = (errs) => errs.filter((e) => !BASELINE_NOISE.some((re) => re.test(e)));
// 默认激活会话即根会话（fresh home 单会话）——断言对齐，不做会话切换。
{
  const active = await page.evaluate(async () => {
    const m = await import('/js/state.js');
    return m.default.activeSessionId;
  });
  check('active session is the root Nebula session', active === sid, `active=${active} sid=${sid}`);
  if (active !== sid) {
    await page.evaluate(async (sidArg) => {
      const m = await import('/js/sidebar.js');
      m.switchToSession(sidArg);
    }, sid);
    await page.waitForTimeout(1200);
  }
}

// 等两张节点卡到达（WS 帧嗅探）
{
  const t0 = Date.now();
  let frames = [];
  while (Date.now() - t0 < 60000) {
    frames = await page.evaluate(() => window.__askFrames.filter((f) => f.type === 'askUser'));
    if (frames.length >= 2) break;
    await sleep(1000);
  }
  check('two askUser frames reached the client', frames.length >= 2, `frames=${frames.length}`);
  const projFrames = frames.filter((f) => f.project === PROJECT);
  check('F1 engine: frames carry project field', projFrames.length >= 2, JSON.stringify(frames.map((f) => ({ p: f.project, n: f.nodeName, r: f.requestId }))).slice(0, 200));
  const names = projFrames.map((f) => f.nodeName).sort();
  check('F1 engine: frames carry nodeName probe-a/probe-b', names.join() === 'probe-a,probe-b', names.join());
}

// F1①/F1④/F2①：badge + 常驻条 + header badge
async function barState(p) {
  return p.evaluate(() => ({
    rows: [...document.querySelectorAll('#ask-pending-bar .ask-pending-row')].map((r) => ({
      rid: r.dataset.requestId,
      source: r.querySelector('.ask-pending-source')?.textContent || '',
      summary: r.querySelector('.ask-pending-summary')?.textContent || '',
    })),
    badgeHidden: document.getElementById('pending-asks-indicator')?.classList.contains('hidden'),
    count: document.querySelector('#pending-asks-indicator .pa-count')?.textContent || '',
    cardBadges: [...document.querySelectorAll('.ask-user-source')].map((b) => b.textContent),
  }));
}
{
  const t0 = Date.now();
  let st = null;
  while (Date.now() - t0 < 30000) {
    st = await barState(page);
    if (st.rows.length >= 2 && st.cardBadges.length >= 2) break;
    await sleep(800);
  }
  check('F1①/④ card badges: qa-f1f2 · probe-a / probe-b',
    st.cardBadges.includes(`${PROJECT} · probe-a`) && st.cardBadges.includes(`${PROJECT} · probe-b`),
    st.cardBadges.join(' | '));
  check('F2① bar has 2 rows', st.rows.length === 2, `rows=${st.rows.length}`);
  check('F2① row sources carry project · node', st.rows.every((r) => r.source.startsWith(`${PROJECT} · `)), st.rows.map((r) => r.source).join(' | '));
  const rowB = st.rows.find((r) => r.source.includes('probe-b'));
  check('F2① summary = first-question 40-char truncation (tool summarize 同款)', !!rowB && rowB.summary === expectedSummary(QB), rowB && rowB.summary);
  const rowA = st.rows.find((r) => r.source.includes('probe-a'));
  check('F2① short question NOT truncated (control)', !!rowA && rowA.summary === expectedSummary(QA), rowA && rowA.summary);
  check('F2① header badge visible with count=2', st.badgeHidden === false && st.count === '2', `hidden=${st.badgeHidden} count=${st.count}`);
  await page.screenshot({ path: join(SHOTS, 'f1-two-cards-light.png') });
  console.log(`[shot] ${join(SHOTS, 'f1-two-cards-light.png')}`);
}

// F1① 暗主题：两卡仍 pending（hub 持有）——新开 dark context，replay 重建后
// 断言 badge + 截图（同时旁证 replay 链在第二客户端的保真）。
{
  const dark = await openApp('dark');
  await dark.page.evaluate(async (sidArg) => {
    const m = await import('/js/sidebar.js');
    m.switchToSession(sidArg);
  }, sid);
  const t0 = Date.now();
  let st = null;
  while (Date.now() - t0 < 30000) {
    st = await barState(dark.page);
    if (st.rows.length >= 2 && st.cardBadges.length >= 2) break;
    await sleep(800);
  }
  check('F1① dark: badges rebuilt via replay', st.cardBadges.includes(`${PROJECT} · probe-a`) && st.cardBadges.includes(`${PROJECT} · probe-b`), st.cardBadges.join(' | '));
  check('F1① dark: bar 2 rows + count=2', st.rows.length === 2 && st.count === '2', `rows=${st.rows.length} count=${st.count}`);
  await dark.page.screenshot({ path: join(SHOTS, 'f1-two-cards-dark.png') });
  console.log(`[shot] ${join(SHOTS, 'f1-two-cards-dark.png')}`);
  check('F2④ zero console errors (dark session, baseline-noise filtered)', realErrors(dark.consoleErrors).length === 0, realErrors(dark.consoleErrors).slice(0, 3).join(' ; ') || 'clean');
  await dark.browser.close();
}

// F1③/F2③：刷新重连 → replayed 帧对账重建
{
  await page.reload({ waitUntil: 'domcontentloaded' });
  await page.waitForSelector('#search-btn', { state: 'visible', timeout: 30_000 });
  await page.waitForTimeout(2500);
  const replayed = await page.evaluate(() => window.__askFrames.filter((f) => f.type === 'askUser' && f.replayed === true));
  check('F1③ replayed frames re-arrived after refresh', replayed.length >= 2, `replayed=${replayed.length}`);
  check('F1③ replayed frames carry project/nodeName (ListPendingAsks fidelity)',
    replayed.filter((f) => f.project === PROJECT && ['probe-a', 'probe-b'].includes(f.nodeName)).length >= 2,
    JSON.stringify(replayed.map((f) => ({ p: f.project, n: f.nodeName }))).slice(0, 200));
  // 切回根会话后 DOM 断言
  await page.evaluate(async (sidArg) => {
    const m = await import('/js/sidebar.js');
    m.switchToSession(sidArg);
  }, sid);
  await page.waitForTimeout(1500);
  const st = await barState(page);
  check('F2③ bar rebuilt to 2 rows after refresh', st.rows.length === 2, `rows=${st.rows.length}`);
  check('F2③ badge count=2 after refresh', st.count === '2', `count=${st.count}`);
  check('F1③ badges intact after refresh', st.cardBadges.includes(`${PROJECT} · probe-a`) && st.cardBadges.includes(`${PROJECT} · probe-b`), st.cardBadges.join(' | '));
}

// F1②：Nebula 自问（badge 抑制不回归）——fire-and-forget（turn 挂起等回答）
{
  // 等 Nebula 静止（分发器投递轮回合收尾）再发问——否则 selfask 被注入运行中
  // 回合（immediate-input-injected-at-tools-complete），时序难断言。
  {
    const t0 = Date.now();
    let lastCount = -1, still = 0;
    while (Date.now() - t0 < 20000) {
      const n = captured.length;
      if (n === lastCount) { still++; if (still >= 3) break; } else still = 0;
      lastCount = n;
      await sleep(800);
    }
  }
  fetch(`${BASE}/api/sessions/${sid}/turn`, {
    method: 'POST',
    headers: { authorization: `Bearer ${TOKEN}`, 'content-type': 'application/json' },
    body: JSON.stringify({ content: 'F1F2 selfask：立即调用 AskUserQuestion 工具问我一个问题（两个选项）。除此之外什么都不要做。', timeoutSec: 300 }),
  }).catch(() => {});
  const t0 = Date.now();
  let nebulaFrame = null;
  while (Date.now() - t0 < 60000) {
    const frames = await page.evaluate(() => window.__askFrames.filter((f) => f.type === 'askUser'));
    nebulaFrame = frames.find((f) => f.agentName === 'Nebula' && !f.project);
    if (nebulaFrame) break;
    await sleep(1000);
  }
  check('F1② Nebula self-ask frame arrived (no project field)', !!nebulaFrame, nebulaFrame ? `rid=${nebulaFrame.requestId}` : `none — stub tail: ${captured.slice(-6).map((c) => `${c.tag}[${c.lastRole}:${(c.lastText || '').slice(0, 60)}]`).join(' | ')}`);
  await page.waitForTimeout(1500);
  // Nebula 卡 = 无 badge 的 option-box
  const cardInfo = await page.evaluate(() => {
    const boxes = [...document.querySelectorAll('.option-box')];
    return boxes.map((b) => ({
      rid: b.dataset.requestId || '',
      hasBadge: !!(b.closest('.bubble.ai')?.querySelector('.ask-user-source')),
      answered: !!b.querySelector('.option-answer'),
    }));
  });
  const nebulaCard = cardInfo.find((c) => c.rid === (nebulaFrame?.requestId || ''));
  check('F1② Nebula card has NO source badge (no regression)', !!nebulaCard && !nebulaCard.hasBadge, JSON.stringify(cardInfo));
  const st = await barState(page);
  check('F2① bar=3 with Nebula ask pending', st.rows.length === 3 && st.count === '3', `rows=${st.rows.length} count=${st.count}`);
  await page.screenshot({ path: join(SHOTS, 'f1-three-cards-light.png') });

  // F2②-a 回答路：Nebula 卡点选项一 + 确认 → 条目摘除
  if (nebulaFrame) {
    await page.evaluate((rid) => {
      const box = document.querySelector(`.option-box[data-request-id="${rid}"]`);
      box.querySelector('.option-btn')?.click();
    }, nebulaFrame.requestId);
    await page.waitForTimeout(300);
    await page.evaluate((rid) => {
      const box = document.querySelector(`.option-box[data-request-id="${rid}"]`);
      box.querySelector('.option-confirm')?.click();
    }, nebulaFrame.requestId);
    await page.waitForTimeout(1200);
    const st2 = await barState(page);
    check('F2② answer path: bar entry removed (2 left)', st2.rows.length === 2 && st2.count === '2', `rows=${st2.rows.length} count=${st2.count}`);
  }
}

// F2②-b 取消路：probe-a 卡点取消
const ridOf = async (node) => page.evaluate((n) => {
  const f = window.__askFrames.find((x) => x.type === 'askUser' && x.nodeName === n);
  return f ? f.requestId : null;
}, node);
{
  const ridA = await ridOf('probe-a');
  check('have probe-a requestId', !!ridA);
  if (ridA) {
    await page.evaluate((rid) => {
      const box = document.querySelector(`.option-box[data-request-id="${rid}"]`);
      box.querySelector('.option-cancel')?.click();
    }, ridA);
    await page.waitForTimeout(1200);
    const st = await barState(page);
    check('F2② cancel path: bar entry removed (1 left)', st.rows.length === 1 && st.count === '1', `rows=${st.rows.length} count=${st.count}`);
    check('F2② remaining row is probe-b', st.rows[0] && st.rows[0].source.includes('probe-b'), st.rows.map((r) => r.source).join());
  } else {
    check('F2② cancel path: bar entry removed (1 left)', false, 'no probe-a requestId — upstream chain failed');
    check('F2② remaining row is probe-b', false, 'skipped');
  }
}

// F2②-c 直答路：chat-input 文本被 hub 消费为最老 pending（probe-b）的回答
{
  await page.fill('#input', 'F1F2 chat-input 直答：经输入框回答乙。');
  await page.press('#input', 'Enter');
  const t0 = Date.now();
  let answeredFrame = null;
  while (Date.now() - t0 < 30000) {
    answeredFrame = await page.evaluate(() => window.__askFrames.find((f) => f.type === 'askUserAnswered' && f.via === 'chat-input'));
    if (answeredFrame) break;
    await sleep(800);
  }
  check('F2② chat-input direct answer: askUserAnswered(via=chat-input) broadcast', !!answeredFrame);
  await page.waitForTimeout(1000);
  const st = await barState(page);
  check('F2② direct-answer path: bar empty + badge hidden', st.rows.length === 0 && st.badgeHidden === true, `rows=${st.rows.length} hidden=${st.badgeHidden}`);
  await page.screenshot({ path: join(SHOTS, 'f2-bar-empty-light.png') });
}

// 点击定位+高亮（F2 锚点跳转）：再造一张卡太重——用既有逻辑单元素验证：
// 直答后无 pending，跳转无从验证；改为在双卡阶段已截图证明条存在。此处验
// askUserClosed 前端处理就位（来源死亡路已知边界——引擎触发归批 E2）：
// 直接在页面上下文派发一帧进 handler 链不可行（模块作用域），改为断言
// ws.js 已注册该类型（TERMINAL_MSG_TYPES 路由表含 askUserClosed → 帧能到达
// main.js handler）。
{
  const routing = await page.evaluate(async () => {
    const src = await (await fetch('/js/ws.js')).text();
    return src.includes("'askUserClosed'");
  });
  check('F2 known-boundary: askUserClosed routing registered (engine trigger = batch E2)', routing);
}

// F2④ console 零错误
check('F2④ zero console errors (light session, baseline-noise filtered)', realErrors(light.consoleErrors).length === 0, realErrors(light.consoleErrors).slice(0, 3).join(' ; ') || 'clean');

// 引擎留痕：node-ask 事件 ×2
{
  const logPath = join(WS_DIR, '.nebflow', 'flow-map-events.jsonl');
  let lines = [];
  for (let i = 0; i < 10 && lines.length < 2; i++) {
    if (existsSync(logPath)) lines = readFileSync(logPath, 'utf8').trim().split('\n').filter((l) => l.includes('"node-ask"'));
    if (lines.length < 2) await sleep(1000);
  }
  check('engine audit: node-ask events written (>=2)', lines.length >= 2, `found=${lines.length} at ${logPath}`);
  const text = lines.join('\n');
  check('engine audit: events name probe-a and probe-b', text.includes('probe-a') && text.includes('probe-b'), lines[0]?.slice(0, 160) || '');
}

await light.browser.close();

// post-flight ②：收尾前再验 8080 宿主集合
{
  const after = execSync('lsof -nP -iTCP:8080 -sTCP:LISTEN -t 2>/dev/null | sort | tr "\\n" " "', { shell: '/bin/bash' }).toString().trim();
  check('host 8080 listener set unchanged (final)', after === H8080_BEFORE, `before=[${H8080_BEFORE}] after=[${after}]`);
}

await cleanup();
// 删除点二次断言；默认不删（要清理设 CLEAN=1）——非 tmp 隔离 home 在此处也会被拒
safeRm(HOME, { label: 'e2e-askuser-source-label' });
console.log(failed === 0 ? '\nALL PASS' : `\n${failed} CHECK(S) FAILED`);
process.exit(failed === 0 ? 0 : 1);
