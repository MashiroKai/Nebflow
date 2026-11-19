// composer-resync-live.spec.mjs — 主会话 composer 幽忙修复（2026-10-28）实机验收。
//
// 复刻作者报障链：回复气泡与 turn-header 已完成（done 已发），但 composer 仍是
// 停止钮。关键形态不是普通刷新，而是 **done 落在浏览器 WS 断连黑暗窗内**——
// 终态帧不重放，旧前端只有重连后的 activeAgents 快照（显式豁免活跃主会话），
// 于是断线前的 busySessionIds 成为永久幽忙。
//
// 真实全环：隔离 NEBFLOW_HOME + assembly jar（--home/--port/--no-browser）+
// OpenAI 兼容 SSE stub LLM + 真 Chromium。基线（红）/修复（绿）同一剧本，仅
// WEB_ROOT 切换：红 = git HEAD 导出的旧 web 树前置 classpath（旧前端），绿 =
// 本支 web 树前置（新前端）。后端均含新 getSessionBusy 协议，差异面钉死在前端。
//
// 场景：
//   A 多气泡工具轮：UI 发消息 → 文本气泡 + 真 Glob 工具卡 + 第二文本气泡 →
//     done 后 send 恢复（既有正常轮回归钉）。
//   B 断连丢 done：页面 WS 掐断并封构造器 → REST turn 全程在黑暗窗内完成
//     （独立 Bearer WS 探针捕到 done，且时间晚于进入离线时刻）→ 重连。
//     旧前端 = 停止钮幽忙（红）；新前端 = 重连 getSessionBusy权威 false 收口。
//   C 外部回执注入：/api/callbacks/inject（ExternalEvent/inject）→ composer
//     开轮、注入气泡可见、done 收口（delegate 回执语义不回归）。
//
// 收尾纪律：**零信号**——隔离 JVM 靠生产 stdin quit 面（GatewayMain.waitForQuit
// 的 q/quit/exit）自然退出；finally 关浏览器 + mock server，home 用删除守卫清理。
// 不得触碰在产引擎/进程。在产 6138 与本脚本端口无关。
//
// Run（worktree 根）：
//   EXPECT=red  WEB_ROOT=/tmp/src/main/resources/web GATEWAY_PORT=8690 MOCK_PORT=18690 \
//     SHOTS=.nebflow/evidence/20261028_composer-resync/red node tests/composer-resync-live.spec.mjs
//   EXPECT=green WEB_ROOT=$PWD/src/main/resources/web ... /green node ...
//
// EXPECT 语义：red 只允许 B 幽忙断言失败（= 病因复现），其余必须全过；green 全过。

import { spawn } from 'node:child_process';
import http from 'node:http';
import { readFileSync, mkdirSync, writeFileSync, rmSync, existsSync, readdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { tmpdir } from 'node:os';
import { setTimeout as sleep } from 'node:timers/promises';
import { chromium } from 'playwright-core';
import WebSocket from 'ws';
import { guardFixtureHome, safeRm } from '../scripts/lib/delguard.mjs';
import { establishSession } from './helpers/session-rail.mjs';

const REPO = dirname(dirname(fileURLToPath(import.meta.url)));
const MODE = (process.env.EXPECT || 'green').toLowerCase();
const GATEWAY_PORT = Number(process.env.GATEWAY_PORT || 8690);
const MOCK_PORT = Number(process.env.MOCK_PORT || 18690);
const BASE = `http://127.0.0.1:${GATEWAY_PORT}`;
const HOME = process.env.NEBFLOW_HOME || join(tmpdir(), `qa-composer-resync-${MODE}-${Date.now()}`);
const WEB_ROOT = process.env.WEB_ROOT || join(REPO, 'src', 'main', 'resources', 'web');
// StaticRoutes 的 dev-tree 判据是 **java 进程 cwd** 下的 src/main/resources/web
// （不是 classpath）；WEB_ROOT 因此同时驱动 cwd，红/绿才真正切换前端树。
const WEB_REPO_ROOT = join(WEB_ROOT, '..', '..', '..');
const SHOTS = process.env.SHOTS || join(REPO, '.nebflow', 'evidence', '20261028_composer-resync', MODE);
const JAR = process.env.JAR || (() => {
  const dir = join(REPO, 'target/scala-3.5.2');
  const jars = readdirSync(dir).filter((f) => f.startsWith('stand-assembly-') && f.endsWith('.jar'));
  if (!jars.length) throw new Error('assembly jar not found — run sbt assembly first');
  return join(dir, jars.sort().pop());
})();
// 静态文件的 classloader 资源根是 `web/...`，所以 classpath 必须前置 WEB_ROOT
// 的上一级（…/resources）；cwd 腿另用 WEB_ROOT 本身（见上）。
const CLASSPATH = `${join(WEB_ROOT, '..')}:${JAR}`;
const OUTAGE_MS = 4500;

let failed = 0;
const results = [];
function check(name, ok, extra = '') {
  results.push({ name, ok: !!ok });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!ok) failed++;
}

// ── 端口预检（ occupied 即中止；绝不探/碰在产 6138）──
for (const port of [GATEWAY_PORT, MOCK_PORT]) {
  const out = (await import('node:child_process')).execSync(
    `lsof -nP -iTCP:${port} -sTCP:LISTEN 2>/dev/null || true`, { shell: '/bin/bash' }).toString().trim();
  if (out) {
    console.error(`port ${port} occupied — abort (not touching it): ${out}`);
    process.exit(1);
  }
}

// ── stub LLM ───────────────────────────────────────────────────────────────
const captured = [];
const msgText = (m) => (typeof m?.content === 'string' ? m.content
  : Array.isArray(m?.content) ? m.content.map((p) => p?.text ?? (p?.type === 'text' ? p.text : '')).join('\n') : '');
const chunk = (delta, finish = null) => 'data: ' + JSON.stringify({
  id: 'chatcmpl-live', object: 'chat.completion.chunk', created: Math.floor(Date.now() / 1000), model: 'mock-live',
  choices: [{ index: 0, delta, finish_reason: finish }],
}) + '\n\n';
function stream(events) { events.push('data: [DONE]\n\n'); return events.join(''); }
function finalText(text) {
  return stream([chunk({ role: 'assistant', content: '' }), chunk({ content: text }), chunk({}, 'stop')]);
}
function mockStreamFor(body) {
  const msgs = body.messages || [];
  const last = msgs[msgs.length - 1] || {};
  const blob = JSON.stringify(msgs);
  // 引擎会在真实 user 之后追加 system-reminder/time 等消息，不能拿“最后一条 user”
  // 判分支；全请求 blob 的场景 token 才是稳定判据。tool-followup 仍以 OpenAI 形态
  // 的 role=tool 收口（上一 assistant turn 必然带 tool_calls）。
  // 场景 token 必须取**最后一条真人语义 user**（引擎会在其后追加 system-reminder
  // 的 user 行，且历史里永久留着旧 token——全 blob 判据会让后续所有轮都误回旧分支）。
  const scenarioUser = [...msgs].reverse().find((m) => m.role === 'user' && /LIVE_(TOOL|OFFLINE|INJECT)_/.test(msgText(m)));
  const scenarioToken = scenarioUser ? (msgText(scenarioUser).match(/LIVE_(TOOL|OFFLINE|INJECT)_/) || [])[0] : '';
  const branch = last.role === 'tool' ? 'tool-followup'
    : scenarioToken === 'LIVE_TOOL_' ? 'tool-turn'
      : scenarioToken === 'LIVE_OFFLINE_' ? 'offline-turn'
        : scenarioToken === 'LIVE_INJECT_' ? 'inject-turn' : 'plain';
  captured.push({ at: Date.now(), lastRole: last.role, branch, token: scenarioToken });
  console.log(`[mock] role=${last.role} branch=${branch} token=${scenarioToken}`);
  // B/C 的时序控制腿：先让浏览器收到权威 sessionBusy(true)/可见开轮，再让终态
  // 到场（B 中浏览器已离线）/注入停止态可被读到（C）。其余分支零延迟。
  const preDelay = branch === 'offline-turn' ? 1200 : branch === 'inject-turn' ? 900 : 0;
  const payload = async () => {
    if (preDelay) await sleep(preDelay);
    if (branch === 'tool-followup') return finalText('LIVE_FINAL_AFTER_TOOL');
    if (branch === 'tool-turn') {
      return stream([
        chunk({ role: 'assistant', content: '' }),
        chunk({ content: 'LIVE_TEXT_BEFORE_TOOL' }),
        chunk({ tool_calls: [{ index: 0, id: 'call_glob_live', type: 'function', function: { name: 'Glob', arguments: JSON.stringify({ pattern: '*.json', path: HOME }) } }] }),
        chunk({}, 'tool_calls'),
      ]);
    }
    return finalText('LIVE_FINAL_TEXT');
  };
  return payload();
}
function startMock() {
  const server = http.createServer((req, res) => {
    if (req.method === 'POST' && req.url === '/v1/chat/completions') {
      let raw = '';
      req.on('data', (d) => { raw += d; });
      req.on('end', async () => {
        let body = {}; try { body = JSON.parse(raw); } catch { /* tolerate */ }
        res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-cache', connection: 'keep-alive' });
        res.end(await mockStreamFor(body));
      });
      return;
    }
    res.writeHead(404).end();
  });
  return new Promise((resolve) => server.listen(MOCK_PORT, '127.0.0.1', () => resolve(server)));
}

// ── fixture home ───────────────────────────────────────────────────────────
function buildFixture() {
  safeRm(HOME, { label: 'composer-resync-live/buildFixture' });
  mkdirSync(HOME, { recursive: true });
  writeFileSync(join(HOME, 'nebflow.json'), JSON.stringify({
    // auto-all = 隔离 fixture 的明确最小配置：Glob 免确认，A 轮可在无人在环下完成。
    safety: { defaultMode: 'auto-all' },
    llm: { providers: { mock: { baseUrl: `http://127.0.0.1:${MOCK_PORT}/v1/`, apiKey: 'sk-live-stub', protocol: 'openai', models: [{ id: 'mock-live', maxTokens: 8192, contextWindow: 128000 }] } } },
  }));
  writeFileSync(join(HOME, 'onboarding.json'), JSON.stringify({ state: 'skipped' }));
}

// ── 隔离 JVM（生产 stdin quit 收尾，零信号）───────────────────────────────
let gw = null;
let gwLogs = [];
function startGateway() {
  gw = spawn('java', [
    '--add-opens', 'java.base/java.lang=ALL-UNNAMED',
    '-cp', CLASSPATH, 'nebflow.Main',
    '--home', HOME, '--port', String(GATEWAY_PORT), '--no-browser', '--foreground', 'start',
  ], {
    cwd: WEB_REPO_ROOT,
    stdio: ['pipe', 'pipe', 'pipe'],
    env: { ...process.env, NEBFLOW_GATEWAY_PORT: String(GATEWAY_PORT) },
  });
  const drain = (d) => {
    const s = String(d);
    gwLogs.push(s);
    if (gwLogs.length > 500) gwLogs.shift();
    if (/error|Exception|started|Started|listen|Listen|Gateway|quit/i.test(s)) console.log(`[gw] ${s.trim().slice(0, 240)}`);
  };
  gw.stdout.on('data', drain);
  gw.stderr.on('data', drain);
}
async function waitGateway(timeoutMs = 90000) {
  const t0 = Date.now();
  while (Date.now() - t0 < timeoutMs) {
    try { const r = await fetch(BASE); if (r.status < 500) return true; } catch { /* booting */ }
    await sleep(500);
  }
  return false;
}
async function stopGateway() {
  if (!gw || gw.exitCode !== null || gw.signalCode) return;
  await new Promise((resolve) => {
    const done = () => resolve();
    gw.once('exit', done);
    try { gw.stdin.write('q\n'); } catch { done(); }
    setTimeout(() => {
      console.error('[cleanup] isolated JVM did not exit on stdin q — leaving it alive (zero-signal discipline); report this');
      resolve();
    }, 25000).unref();
  });
  await sleep(300);
}

let mockServer = null;
let browser = null;
async function cleanup() {
  try { if (browser) await browser.close(); } catch { /* already */ }
  try { mockServer?.closeAllConnections?.(); } catch { /* node version */ }
  try { mockServer?.close(); } catch { /* already */ }
  await stopGateway();
  const still = (await import('node:child_process')).execSync(
    `lsof -nP -iTCP:${GATEWAY_PORT} -sTCP:LISTEN 2>/dev/null || true`, { shell: '/bin/bash' }).toString().trim();
  if (still) console.error(`[cleanup] WARNING isolated gateway still listening: ${still}`);
}
for (const evt of ['SIGINT', 'SIGTERM', 'uncaughtException', 'unhandledRejection']) {
  process.on(evt, async (e) => { if (e) console.error(e); await cleanup(); safeRm(HOME, { label: 'composer-resync-live/failure' }); process.exit(1); });
}

// ── 读数助手 ───────────────────────────────────────────────────────────────
async function composerState(page) {
  return page.evaluate(async () => {
    const st = (await import('/js/state.js')).default;
    const vis = (el) => !!el && getComputedStyle(el).display !== 'none';
    return {
      connected: st.connected === true,
      busy: st.activeSessionId ? st.busySessionIds.has(st.activeSessionId) : false,
      activeSessionId: st.activeSessionId,
      send: vis(document.getElementById('send-btn')),
      stop: vis(document.getElementById('stop-btn')),
    };
  });
}
async function waitComposer(page, pred, timeoutMs, label) {
  const t0 = Date.now();
  let last = null;
  while (Date.now() - t0 < timeoutMs) {
    last = await composerState(page);
    if (pred(last)) return last;
    await sleep(150);
  }
  throw new Error(`composer wait timeout (${label}): ${JSON.stringify(last)}`);
}
async function waitPageFrame(page, spec, timeoutMs, label) {
  // Playwright 不能序列化谓词函数；帧筛选面用可序列化 spec（type/session/子串）。
  await page.waitForFunction((q) => window.__frames.some((f) =>
    (!q.type || f.type === q.type) &&
    (!q.sid || f.sessionId === q.sid) &&
    (!q.textIncludes || String(f.text || '').includes(q.textIncludes)) &&
    (q.injected === undefined || f.injected === q.injected)
  ), spec, { timeout: timeoutMs });
}
async function activeRootSession(api) {
  const r = await api('/sessions');
  const list = r.json?.sessions || r.json || [];
  const sid = (list.filter((s) => s?.agentName === 'Nebula')[0] || list[0])?.id;
  if (!sid) throw new Error(`no root session: ${JSON.stringify(r.json).slice(0, 300)}`);
  return { status: r.status, sid };
}
async function switchTo(page, sid) {
  await page.evaluate(async (target) => {
    const m = await import('/js/sidebar.js');
    m.switchToSession(target);
  }, sid);
  await waitComposer(page, (s) => s.activeSessionId === sid, 8000, 'switch session');
}

// ── 裸 WS 探针（Bearer；只旁观捕帧，不发言）───────────────────────────────
class Probe {
  constructor() { this.events = []; this.ws = null; }
  async connect() {
    const token = JSON.parse(readFileSync(join(HOME, 'auth.json'), 'utf8'));
    this.ws = new WebSocket(`ws://127.0.0.1:${GATEWAY_PORT}/ws`, { headers: { Authorization: `Bearer ${token}` } });
    this.ws.onmessage = (e) => {
      try { this.events.push({ at: Date.now(), frame: JSON.parse(e.data) }); } catch { /* binary */ }
    };
    await new Promise((res, rej) => {
      const t = setTimeout(() => rej(new Error('probe ws timeout')), 15000);
      this.ws.once('open', () => { clearTimeout(t); res(); });
      this.ws.once('error', (err) => { clearTimeout(t); rej(err); });
    });
  }
  find(pred) { return this.events.find((e) => pred(e.frame, e.at)) || null; }
  close() { try { this.ws.close(); } catch { /* already */ } }
}

guardFixtureHome(HOME, { label: 'composer-resync-live' });
mockServer = await startMock();
console.log(`[live] mock LLM :${MOCK_PORT}; mode=${MODE}; web=${WEB_ROOT}; jar=${JAR}`);
buildFixture();
startGateway();
if (!(await waitGateway())) {
  console.error('[live] isolated gateway never came up');
  console.error(gwLogs.join('').slice(-4000));
  await cleanup(); safeRm(HOME, { label: 'composer-resync-live/boot-fail' }); process.exit(1);
}
check('isolated gateway up (real engine, isolated home)', true, BASE);

const TOKEN = JSON.parse(readFileSync(join(HOME, 'auth.json'), 'utf8'));
async function api(path, method = 'GET', body) {
  const res = await fetch(`${BASE}/api${path}`, {
    method,
    headers: { authorization: `Bearer ${TOKEN}`, 'content-type': 'application/json' },
    body: body ? JSON.stringify(body) : undefined,
  });
  return { status: res.status, json: await res.json().catch(() => null) };
}

const { status: sessStatus, sid } = await activeRootSession(api);
check('boot-created root Nebula session found', sessStatus === 200 && !!sid, `sid=${sid}`);

browser = await chromium.launch();
const context = await browser.newContext({ baseURL: BASE, viewport: { width: 1440, height: 900 } });
await context.addInitScript((token) => {
  localStorage.setItem('nebflow_token', token);
  window.__frames = [];
  window.__outFrames = [];
  window.__wsBlocked = false;
  window.__ws = null;
  const OrigWS = window.WebSocket;
  window.WebSocket = class extends OrigWS {
    constructor(...args) {
      if (window.__wsBlocked) throw new Error('qa outage: WebSocket construction blocked');
      super(...args);
      window.__ws = this;
      this.addEventListener('message', (ev) => {
        try { window.__frames.push(JSON.parse(ev.data)); } catch { /* non-JSON */ }
      });
      // 出站帧也记账（getSessionBusy 是请求面；只记入站会漏掉它）。
      const rawSend = this.send.bind(this);
      this.send = (data) => {
        try { window.__outFrames.push(JSON.parse(data)); } catch { /* non-JSON */ }
        return rawSend(data);
      };
    }
  };
}, TOKEN);
const page = await context.newPage();
const pageErrors = [];
// 隔离 fresh home 的既有契约噪声（既有 e2e 同款白名单）：无头像/无 canvas 存档、
// nf-authcheck 403（Stage D 探针信号）、404 agent.json / portal 读取。其余即真错。
const BASELINE_NOISE = [
  /403 \(Forbidden\) .*\/api\/nf-authcheck/,
  /403 \(Forbidden\) .*\/api\/nf-asset-stamp/,
  /404 \(Not Found\) .*\/api\/agents\/Nebula\/avatar\/meta/,
  /404 \(Not Found\) .*\/api\/canvas-tabs/,
  /404 \(Not Found\) .*\/api\/portal\/status/,
  /404 \(Not Found\) .*\/agents\/Nebula\/agent\.json/,
  /404 \(Not Found\) .*\/api\/auth\/login-config/,
  /404 \(Not Found\) .*\/api\/plugin-assets\//,
  /qa outage: WebSocket construction blocked/,
];
const filteredPageErrors = () => pageErrors.filter((e) => !BASELINE_NOISE.some((re) => re.test(e)));
page.on('pageerror', (e) => pageErrors.push(`pageerror: ${e.message}`));
page.on('console', (m) => { if (m.type() === 'error') pageErrors.push(`console.error: ${m.text()} ${m.location()?.url || ''}`); });
// Stage D 认证链：浏览器唯一入门 = console-ticket（Bearer 铸一次性票 → 303 落
// HttpOnly session cookie）；裸 localStorage token 不再被 WS 握手接受。
await establishSession(BASE, TOKEN, page, { waitUntil: 'domcontentloaded' });
await page.waitForSelector('#input', { state: 'visible', timeout: 30000 });
try {
  await waitComposer(page, (s) => s.connected && s.activeSessionId, 20000, 'boot');
} catch (e) {
  const diag = await page.evaluate(() => ({
    frames: window.__frames.slice(-20),
    wsState: window.__ws?.readyState,
    url: location.href,
    storage: Object.fromEntries(Object.entries(localStorage).filter(([k]) => k.endsWith('_token'))),
    body: document.body.innerText.slice(0, 500),
  })).catch((x) => ({ diagError: String(x) }));
  console.error('[diag] boot failure', JSON.stringify(diag, null, 2), '\npageErrors=', pageErrors.slice(0, 10));
  throw e;
}
await switchTo(page, sid);

// ═══════════════════════════════════════════════════════════════════════════
// A — 多气泡工具轮：正常 done 清 busy
// ═══════════════════════════════════════════════════════════════════════════
{
  await page.fill('#input', 'LIVE_TOOL_TURN 请调用 Glob 工具，然后总结。');
  await page.press('#input', 'Enter');
  await waitComposer(page, (s) => s.busy && s.stop, 12000, 'A turn start');
  await waitPageFrame(page, { type: 'toolEnd', sid }, 30000, 'A glob toolEnd');
  await waitPageFrame(page, { type: 'done', sid }, 30000, 'A done');
  const dom = await page.evaluate(() => ({
    aiRows: document.querySelectorAll('.row.ai .bubble.ai').length,
    toolRows: document.querySelectorAll('.row.tool .tool-card').length,
    text: document.body.textContent,
  }));
  check('A multi-bubble turn rendered text + real tool card + second AI bubble',
    dom.aiRows >= 2 && dom.toolRows >= 1 && dom.text.includes('LIVE_TEXT_BEFORE_TOOL') && dom.text.includes('LIVE_FINAL_AFTER_TOOL'),
    `ai=${dom.aiRows} tool=${dom.toolRows}`);
  const st = await waitComposer(page, (s) => !s.busy && s.send && !s.stop, 10000, 'A idle');
  check('A done clears composer to send form', st.send && !st.stop, JSON.stringify(st));
  mkdirSync(SHOTS, { recursive: true });
  await page.screenshot({ path: join(SHOTS, 'A-multi-bubble-idle.png') });
}

// ═══════════════════════════════════════════════════════════════════════════
// B — done 落在断连黑暗窗内 → 重连权威重同步
// ═══════════════════════════════════════════════════════════════════════════
{
  const probe = new Probe();
  await probe.connect();
  // 先以 UI 发起 B 轮并**等到权威 sessionBusy(true) 已被浏览器消费**：这是报障
  // 的必要前置——否则离线期间乐观置位会被既有 turn-start 确认网合法收掉，复刻的
  // 就不是“重连幽忙”这一根因。mock 对 offline-turn 先延迟，保证这个窗口存在。
  await page.fill('#input', 'LIVE_OFFLINE_TURN 只回答 LIVE_FINAL_TEXT 即可。');
  await page.press('#input', 'Enter');
  await waitPageFrame(page, { type: 'sessionBusy', sid }, 10000, 'B authoritative busy before outage');
  const busyBeforeOutage = await composerState(page);
  check('B browser consumed authoritative busy=true before outage', busyBeforeOutage.busy && busyBeforeOutage.stop, JSON.stringify(busyBeforeOutage));

  // 封新连接 + 掐活连接：随后 scheduleReconnect 的所有构造都失败，直到我们放行。
  await page.evaluate(() => {
    window.__wsBlocked = true;
    if (window.__ws && window.__ws.readyState <= 1) window.__ws.close(4000, 'qa-outage');
  });
  await waitComposer(page, (s) => s.connected === false, 8000, 'B offline');
  const offlineAt = Date.now();

  // done 在黑暗窗内到场；独立 Bearer WS 探针旁观权威帧。
  const doneEvt = await (async () => {
    const t0 = Date.now();
    while (Date.now() - t0 < 45000) {
      const hit = probe.find((f, at) => f.type === 'done' && f.sessionId === sid && at >= offlineAt);
      if (hit) return hit;
      await sleep(120);
    }
    return null;
  })();
  await page.waitForFunction((q) => window.__frames.some((f) => f.type === q.type && f.sessionId === q.sid), { type: 'done', sid }, { timeout: 1000 }).catch(() => null);
  check('B done was emitted while the browser was offline (independent WS probe)',
    !!doneEvt && doneEvt.at >= offlineAt,
    doneEvt ? `doneAt=+${doneEvt.at - offlineAt}ms` : 'none');
  check('B completed turn is already in engine state before reconnect', !!doneEvt, '');

  // 重连前：报障本体形态（回复已完成但停止钮仍在）。
  const stale = await composerState(page);
  check('B offline stale composer keeps stop form after engine-side done', stale.busy && stale.stop && !stale.send, JSON.stringify(stale));
  mkdirSync(SHOTS, { recursive: true });
  await page.screenshot({ path: join(SHOTS, 'B-offline-stale-stop.png') });

  // 放行构造器并立即重连（不等指数退避）。
  await page.evaluate(() => { window.__wsBlocked = false; });
  await page.evaluate(async () => { (await import('/js/ws.js')).forceReconnect(); });
  await waitComposer(page, (s) => s.connected, 12000, 'B reconnect');
  // 诊断：旧树若仍被清 busy，需要知道是哪枚重连帧干的（red 复现定位证据）。
  await page.waitForTimeout(500);
  console.log('[diag] reconnect frames:', JSON.stringify(await page.evaluate(() =>
    window.__frames.filter((f) => ['sessionBusy', 'sessionBusyStates', 'getSessionBusy', 'done', 'historyPage', 'activeAgents', 'activeBgTasks', 'sessionList'].includes(f.type)).map((f) => ({
      t: f.type, sid: f.sessionId, busy: f.busy,
      histories: f.type === 'historyPage' ? (f.messages || []).length : undefined,
      agents: f.type === 'activeAgents' ? (f.agents || []).map((a) => `${a.kind}:${a.sessionId}`) : undefined,
      sessions: f.type === 'sessionList' ? (f.sessions || []).map((s) => `${s.id}:${s.busy ? 'busy' : 'idle'}`) : undefined,
      tasks: f.type === 'activeBgTasks' ? Object.keys(f.tasks || {}) : undefined,
    }))
  ), null, 1));

  const after = await (async () => {
    const t0 = Date.now();
    let last = null;
    while (Date.now() - t0 < 10000) {
      last = await composerState(page);
      if (!last.busy && last.send && !last.stop) return { ok: true, ...last };
      await sleep(150);
    }
    return { ok: false, ...last };
  })();
  const syncFrames = await page.evaluate(() => ({
    asked: window.__outFrames.filter((f) => f.type === 'getSessionBusy').length,
    answered: window.__frames.filter((f) => f.type === 'sessionBusyStates').length,
  }));
  if (MODE === 'red') {
    check('RED root cause reproduced: reconnect does NOT clear the stale stop button', !after.ok && after.stop, JSON.stringify(after));
  } else {
    check('GREEN resync asks authoritative session busy on reconnect', syncFrames.asked > 0 && syncFrames.answered > 0, JSON.stringify(syncFrames));
    check('GREEN authoritative false clears stale stop button after reconnect', after.ok, JSON.stringify(after));
  }
  await page.screenshot({ path: join(SHOTS, 'B-after-reconnect.png') });
  probe.close();
}

// ═══════════════════════════════════════════════════════════════════════════
// C — 外部回执注入（callbacks/inject → ExternalEvent/inject）真实轮
// ═══════════════════════════════════════════════════════════════════════════
{
  const before = await composerState(page);
  check('C composer idle before external receipt', !before.busy && before.send && !before.stop, JSON.stringify(before));
  const inj = await api('/callbacks/inject', 'POST', {
    agent: 'Nebula', session: sid, source: 'delegate-receipt',
    message: 'LIVE_INJECT_RECEIPT delegate 已完成，请继续。',
  });
  check('C /api/callbacks/inject accepted', inj.status === 200 && inj.json?.sessionId === sid, JSON.stringify(inj.json));
  // 注入开启真实轮：等权威/本地 busy 任一面把 composer 切到停止形态。
  const running = await (async () => {
    const t0 = Date.now();
    let last = null;
    while (Date.now() - t0 < 12000) {
      last = await composerState(page);
      if (last.busy && last.stop) return { ok: true, ...last };
      await sleep(120);
    }
    return { ok: false, ...last };
  })();
  check('C external injection starts a visible turn (stop form)', running.ok, JSON.stringify(running));
  await waitPageFrame(page, { type: 'user', injected: true, textIncludes: 'LIVE_INJECT_RECEIPT' }, 12000, 'C injected bubble');
  await waitPageFrame(page, { type: 'done', sid }, 30000, 'C done');
  const idle = await waitComposer(page, (s) => !s.busy && s.send && !s.stop, 10000, 'C idle');
  check('C injected turn ends and restores send form', idle.send && !idle.stop, JSON.stringify(idle));
  const domText = await page.evaluate(() => document.body.textContent);
  check('C injected receipt bubble is visible', domText.includes('LIVE_INJECT_RECEIPT') && domText.includes('LIVE_FINAL_TEXT'), '');
  await page.screenshot({ path: join(SHOTS, 'C-external-receipt-idle.png') });
}

check('zero unexpected page errors', filteredPageErrors().length === 0, filteredPageErrors().slice(0, 3).join(' ; ') || 'clean');

await cleanup();
safeRm(HOME, { label: 'composer-resync-live' });

// EXPECT 收敛：red 的病因断言必须红；唯一可容忍的连坐失败 = C 的前置“composer
// 空闲”（幽忙正是病因本体，外部回执前无法要求它已清）。其余全绿；green = 全绿。
const failingNames = results.filter((r) => !r.ok).map((r) => r.name);
let verdict;
if (MODE === 'red') {
  // red 的病因断言本身就是 PASS（观察到幽忙），所以查 results 的 ok 面，而非失败面。
  const rootReproduced = results.some((r) => r.name.includes('RED root cause reproduced') && r.ok);
  const tolerated = failingNames.every((n) =>
    n.includes('RED root cause reproduced') || n === 'C composer idle before external receipt');
  verdict = rootReproduced && tolerated;
} else {
  verdict = failed === 0;
}
console.log(`[${MODE}] ${verdict ? 'EXPECTED RESULT ACHIEVED' : 'UNEXPECTED RESULT'}` +
  (failingNames.length ? ` — failing checks: ${failingNames.join(' | ')}` : ''));
process.exit(verdict ? 0 : 1);
