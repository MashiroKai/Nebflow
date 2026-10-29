#!/usr/bin/env node
// e2e-schedule-card-live.mjs — schedcard 批（2026-10-29，作者令「定时任务卡片
// 防退化 + stand 配色」）LIVE 验证：挂一个 1 分钟一次性定时任务**真触发**，
// 截专属卡实况（亮暗双模式），并断言防退化三面：
//
//   LIVE① 触发即渲染：注入 user 帧（source=schedule）到页 → .bubble.schedule-card
//          在 #chat 出现（chip「定时任务」/正文剥引擎注记/预定时间行），无需刷新；
//   LIVE② 防退化：同刻**零**通用绿气泡承载该内容（.bubble.user 不含任务正文）、
//          零退役瞬态 toast（.reminder-trigger-bubble 不存在）；
//   LIVE③ 刷新存活：reload 后卡片自后端历史（真实 .ui.json 行）原样重建——
//          落盘行带标记的直接证据。
//
// 验收链（真实全环，模式复用 e2e-askuser-source-label.mjs）：隔离 NEBFLOW_HOME +
// 隔离实例（assembly jar + NEBFLOW_GATEWAY_PORT + --port/--home/--no-browser，
// 绝非 8080 宿主，禁 sbt run）+ OpenAI 兼容 stub LLM。
// 剧本：REST /turn「SCHEDCARD-LIVE-ARM」→ stub 脚本化 Schedule 工具调用
//（action=create，name=LIVE 演示任务，triggerAt="in 1 minute"）→ 引擎落库 →
// ~70s 内 scheduler 10s 轮询到点 → 落盘注入行 + 广播 user 帧 → 页面渲染专属卡。
//
// Run（worktree 根，jar 已构建）：node scripts/e2e-schedule-card-live.mjs
// Env：GATEWAY_PORT=8119 MOCK_PORT=19001；CLEAN=1 跑完拆夹具；QA_SHOTS 改截图道。

import { spawn, execSync } from 'node:child_process';
import http from 'node:http';
import { readFileSync, mkdirSync, writeFileSync, cpSync, rmSync, existsSync, readdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { homedir, tmpdir } from 'node:os';
import { setTimeout as sleep } from 'node:timers/promises';
import { chromium } from '/opt/homebrew/lib/node_modules/playwright/index.mjs';
import { guardFixtureHome, safeRm, assertCleanable } from './lib/delguard.mjs';
import { guardWrites } from './lib/writeguard.mjs';

const REPO = dirname(dirname(fileURLToPath(import.meta.url)));
const GATEWAY_PORT = Number(process.env.GATEWAY_PORT || 8119);
const MOCK_PORT = Number(process.env.MOCK_PORT || 19001);
const BASE = `http://127.0.0.1:${GATEWAY_PORT}`;
const HOME = process.env.NEBFLOW_HOME || join(tmpdir(), 'qa-schedcard-live');
const SHOTS = process.env.QA_SHOTS || join(REPO, '.nebflow', 'evidence', '20261029_schedcard', 'live');
const GENERAL_AGENT_SRC = process.env.GENERAL_AGENT_SRC || join(homedir(), '.nebflow', 'agents', 'general');
const CLEAN = process.env.CLEAN === '1' || process.env.NB_CLEAN === '1';

guardWrites([HOME, SHOTS], { repoRoot: REPO, label: 'e2e-schedule-card-live' });
guardFixtureHome(HOME, { label: 'e2e-schedule-card-live' });

const ARM_TEXT = 'SCHEDCARD-LIVE-ARM：调用 Schedule 工具创建一个一次性任务（除此之外什么都不要做）。';
const CONTENT = 'LIVE 实拍：把昨日纪要整理成三段式发出来。（schedcard 批 1 分钟一次性任务）';

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
  if (CLEAN) {
    try { safeRm(HOME, { label: 'e2e-schedule-card-live/cleanup' }); console.log('[cleanup] fixture home removed'); } catch (e) { console.log(`[cleanup] keep home (${e.message})`); }
  } else {
    console.log(`[cleanup] fixture home kept at ${HOME}（CLEAN=1 可拆）`);
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

// ── stub LLM（OpenAI 兼容流式；脚本化 Schedule 工具调用）──
let armFired = false;
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
function streamFor(body) {
  const msgs = body.messages || [];
  const lastUser = [...msgs].reverse().find((m) => m.role === 'user');
  const turn = lastUser ? msgText(lastUser) : '';
  if (turn.includes('SCHEDCARD-LIVE-ARM')) {
    // 一次性闸：工具结果回填轮（last role=tool）走 finalOk，不再重复开火。
    if (armFired || msgs[msgs.length - 1]?.role === 'tool') return finalOk();
    armFired = true;
    return done([
      chunk({ role: 'assistant', content: '' }),
      chunk(toolCall('call_sched_live', 'Schedule', {
        action: 'create',
        content: CONTENT,
        triggerAt: 'in 1 minute',
        name: 'LIVE 演示任务',
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
  assertCleanable(HOME, { label: 'e2e-schedule-card-live/buildFixture' });
  rmSync(HOME, { recursive: true, force: true });
  mkdirSync(HOME, { recursive: true });
  // agent 定义取宿主实载工件（e2e-askuser-source-label 同款来源；只读拷贝）
  if (!existsSync(GENERAL_AGENT_SRC)) throw new Error(`general agent def not found: ${GENERAL_AGENT_SRC}`);
  cpSync(GENERAL_AGENT_SRC, join(HOME, 'agents', 'general'), { recursive: true });
  writeFileSync(join(HOME, 'nebflow.json'), JSON.stringify({
    llm: { providers: { mock: { baseUrl: `http://127.0.0.1:${MOCK_PORT}/v1/`, apiKey: 'sk-e2e-stub', protocol: 'openai', models: [{ id: 'mock-1', maxTokens: 8192, contextWindow: 128000 }] } } },
  }));
  writeFileSync(join(HOME, 'onboarding.json'), JSON.stringify({ state: 'skipped' }));
}

// ── 隔离实例（assembly jar 配方；NEBFLOW_GATEWAY_PORT env 为主保险）──
function findJar() {
  const dir = join(REPO, 'target/scala-3.5.2');
  const jars = readdirSync(dir).filter((f) => f.startsWith('stand-assembly-') && f.endsWith('.jar'));
  if (!jars.length) throw new Error('assembly jar not found — run sbt assembly first');
  return join(dir, jars.sort().pop());
}
function startGateway() {
  const jar = findJar();
  console.log(`[phase] jar: ${jar}`);
  const child = spawn('java', [
    '--add-opens', 'java.base/java.lang=ALL-UNNAMED',
    '-cp', jar, 'nebflow.Main',
    '--home', HOME, '--port', String(GATEWAY_PORT), '--no-browser', '--foreground', 'start',
  ], {
    detached: true, stdio: ['ignore', 'pipe', 'pipe'],
    env: { ...process.env, NEBFLOW_GATEWAY_PORT: String(GATEWAY_PORT) },
  });
  children.push(child);
  const log = (d) => {
    const s = String(d).trim();
    if (/error|Exception|listen|Listen|started|Started|Gateway|Scheduled|port/i.test(s)) console.log(`[gw] ${s.slice(0, 200)}`);
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

const sessList = await api('/sessions');
const nebulaSessions = (sessList.json?.sessions || sessList.json || []).filter((s) => s.agentName === 'Nebula');
const sid = nebulaSessions[0]?.id;
check('root Nebula session found (boot-created)', sessList.status === 200 && !!sid, JSON.stringify(nebulaSessions[0] || sessList.json).slice(0, 140));

// ── Playwright：真页面、真 WS ──
mkdirSync(SHOTS, { recursive: true });
const browser = await chromium.launch();
const context = await browser.newContext({
  baseURL: BASE, colorScheme: 'light', viewport: { width: 1440, height: 900 }, locale: 'zh-CN',
});
await context.addInitScript((tok) => {
  localStorage.setItem('nebflow_token', tok);
  // WS 帧嗅探：user / scheduledTaskTriggered 帧全量落 window.__frames；
  // 全帧型计数落 window.__frameTypes（诊断：帧没到 socket vs 到了没渲染）。
  window.__frames = [];
  window.__frameTypes = {};
  const OrigWS = window.WebSocket;
  window.WebSocket = class extends OrigWS {
    constructor(...args) {
      super(...args);
      this.addEventListener('message', (ev) => {
        try {
          const msg = JSON.parse(ev.data);
          window.__frameTypes[msg.type] = (window.__frameTypes[msg.type] || 0) + 1;
          if (msg && (msg.type === 'user' || msg.type === 'scheduledTaskTriggered')) window.__frames.push(msg);
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
// 凭据链（真实形态）：AuthAdmin 面（file token only）铸一次性 console 票 →
// `GET /?ticket=` 兑换 HttpOnly 会话 cookie（303 回 /）→ 页面带会话启动 ⇒
// WS 握手（session rail）通过 ⇒ live 帧可达。localStorage token 不进 WS 握手
// （ws.js：握手认 HttpOnly 会话 cookie，stage D 已拔 legacy 轨）。
{
  const mint = await fetch(`${BASE}/api/auth/console-ticket`, {
    method: 'POST', headers: { authorization: `Bearer ${TOKEN}` },
  });
  if (!mint.ok) { console.log(`FAIL  console-ticket mint refused (${mint.status}) — aborting`); await cleanup(); process.exit(1); }
  const { ticket } = await mint.json();
  check('console ticket minted (AuthAdmin face)', !!ticket);
  await page.goto(`${BASE}/?ticket=${encodeURIComponent(ticket)}`, { waitUntil: 'domcontentloaded' });
}
await page.waitForSelector('#search-btn', { state: 'visible', timeout: 30_000 });
const obAppeared = await page.waitForSelector('.onboarding-overlay', { state: 'attached', timeout: 4_000 }).then(() => true).catch(() => false);
if (obAppeared) {
  const skip = await page.$('#ob-skip');
  if (skip) await skip.click(); else await page.click('#ob-no');
  await page.waitForSelector('.onboarding-overlay', { state: 'detached', timeout: 5_000 }).catch(() => {});
}
await page.waitForTimeout(800);
check('console page up', true, BASE);

// 挂任务：走 agent 真链（stub 脚本化 Schedule 工具调用，带 name + in 1 minute）
{
  const t = await api(`/sessions/${sid}/turn`, 'POST', { content: ARM_TEXT, timeoutSec: 90 });
  check('arm turn completed (Schedule tool call scripted)', t.status === 200 || t.status === 202, `status=${t.status}`);
  await sleep(1500);
}

// 等真触发：scheduler 10s 轮询 + in-1-minute ⇒ 上限 110s 内卡必须出现（触发即渲染，无刷新）
let cardOk = false;
let cardInfo = null;
{
  const t0 = Date.now();
  while (Date.now() - t0 < 110_000) {
    cardInfo = await page.evaluate(() => {
      const chat = document.getElementById('chat');
      const card = chat?.querySelector('.bubble.schedule-card[data-nf-schedule]');
      if (!card) return null;
      return {
        chip: card.querySelector('.schedule-card-chip')?.textContent || null,
        name: card.querySelector('.schedule-card-name')?.textContent || null,
        repeat: card.querySelector('.schedule-card-repeat')?.textContent || null,
        body: (card.querySelector('.schedule-card-body')?.textContent || '').trim(),
        time: card.querySelector('.schedule-card-time')?.textContent || null,
        injectedBase: card.classList.contains('injected'),
      };
    }).catch(() => null);
    if (cardInfo) { cardOk = true; break; }
    await sleep(2000);
  }
  check('LIVE① 触发即渲染：专属卡出现（无需刷新）', cardOk, cardInfo ? JSON.stringify(cardInfo).slice(0, 200) : 'card never appeared in 110s');
  if (!cardOk) {
    // 诊断面：帧到没到 socket 层 / 到了之后 DOM 长什么样 / 页面错误清单。
    const diag = await page.evaluate(() => ({
      frameTypes: window.__frameTypes,
      frames: window.__frames.map((f) => ({ type: f.type, source: f.source, injected: f.injected, text: (f.text || '').slice(0, 60), schedule: f.schedule || null })),
      chatRows: Array.from(document.querySelectorAll('#chat > .row')).slice(-6).map((r) => r.className),
      activeSid: (window.__frameTypes, document.querySelector) ? null : null,
    })).catch((e) => ({ evalError: String(e) }));
    console.log('[diag] frameTypes:', JSON.stringify(diag.frameTypes || {}));
    console.log('[diag] frames:', JSON.stringify(diag.frames || []).slice(0, 800));
    console.log('[diag] lastRows:', JSON.stringify(diag.chatRows || []));
  }
}
if (cardOk) {
  check('LIVE①b 卡头：chip=定时任务 + name + 预定时间', cardInfo.chip === '定时任务' && !!cardInfo.name && (cardInfo.time || '').includes('预定'),
    `chip=${cardInfo.chip} name=${cardInfo.name} time=${cardInfo.time}`);
  check('LIVE①c 正文剥引擎注记尾行', cardInfo.body.includes('三段式') && !cardInfo.body.includes('实际触发'), cardInfo.body.slice(0, 60));
  check('LIVE①d 注入行族基类保留（.bubble.injected）', cardInfo.injectedBase === true);

  // 防退化双面
  const anti = await page.evaluate((content) => {
    const chat = document.getElementById('chat');
    const green = Array.from(chat.querySelectorAll('.bubble.user')).filter((b) => b.textContent.includes(content.slice(0, 12)));
    const toast = chat.querySelectorAll('.reminder-trigger-bubble').length;
    const cards = chat.querySelectorAll('.bubble.schedule-card[data-nf-schedule]').length;
    return { greenForContent: green.length, toast, cards };
  }, CONTENT);
  check('LIVE② 防退化：内容零绿泡承载', anti.greenForContent === 0, `green=${anti.greenForContent}`);
  check('LIVE②b 退役瞬态 toast 零复活', anti.toast === 0, `toast=${anti.toast}`);
  check('LIVE②c 恰一张专属卡（无重复）', anti.cards === 1, `cards=${anti.cards}`);

  // 帧嗅探：注入 user 帧带 schedule 载荷
  const frames = await page.evaluate(() => window.__frames);
  const inj = frames.filter((f) => f.type === 'user' && f.source === 'schedule');
  check('LIVE②d 注入 user 帧到场（source=schedule + schedule 载荷）', inj.length === 1 && !!inj[0].schedule && inj[0].injected === true,
    `user-frames=${frames.filter((f) => f.type === 'user').length}`);

  await page.waitForTimeout(400);
  await page.screenshot({ path: join(SHOTS, 'schedcard-live-light.png') });

  // 亮 → 暗（同 DOM，media query 重判）
  await page.emulateMedia({ colorScheme: 'dark' });
  await page.waitForTimeout(300);
  await page.screenshot({ path: join(SHOTS, 'schedcard-live-dark.png') });
  await page.emulateMedia({ colorScheme: 'light' });

  // LIVE③ 刷新存活：reload 后卡片自后端历史原样重建（落盘行带标记的直接证据）
  await page.reload({ waitUntil: 'domcontentloaded' });
  await page.waitForSelector('#search-btn', { state: 'visible', timeout: 30_000 });
  await page.waitForTimeout(2500); // 历史恢复（historyPage → restoreFromBackendHistory）
  const afterReload = await page.evaluate(() => {
    const chat = document.getElementById('chat');
    const card = chat?.querySelector('.bubble.schedule-card[data-nf-schedule]');
    return {
      present: !!card,
      chip: card?.querySelector('.schedule-card-chip')?.textContent || null,
      name: card?.querySelector('.schedule-card-name')?.textContent || null,
      greenForContent: Array.from(chat.querySelectorAll('.bubble.user')).filter((b) => b.textContent.includes('三段式')).length,
    };
  });
  check('LIVE③ 刷新存活：卡片自后端历史重建（落盘行带标记）', afterReload.present && afterReload.chip === '定时任务',
    JSON.stringify(afterReload));
  check('LIVE③b 刷新后无绿泡退化（历史行不落通用分支）', afterReload.greenForContent === 0, `green=${afterReload.greenForContent}`);
  await page.screenshot({ path: join(SHOTS, 'schedcard-live-after-reload-light.png') });
}

// console 噪音白名单（fresh fixture home 既有设计必触发的两条，同 e2e-askuser-source-label 口径）
const whitelist = [
  /http 40[13]: .*\/api\/nf-authcheck/,
  /requestfailed: .*\/api\/nf-authcheck/,
  /console\.error: .*\/api\/nf-authcheck/,
  /http 404: .*\/api\/canvas-tabs/,
  /http 40[13]: .*\/api\/nf-authcheck\?/,
  // fresh home 探针：agentProfile.js 收敛探针 avatar/meta 与 agent.json
  // displayName 探针——夹具家无人像/agent.json，NotFound by design（404）；
  // agent.json 探针在页面导航/刷新即 abort（ERR_ABORTED，探针自带 AbortSignal，
  // 同 e2e-askuser 白名单注记口径）；canvas-tabs 404 = 无存档契约形态
  // （RestApiRoutes「GET → 200 或 404（无存档）」），console.error 双胞胎同放；
  // portal/status 404 = fresh home 无 portal 绑定的契约形态。
  /http 404: .*\/api\/agents\/Nebula\/avatar\/meta/,
  /console\.error: .*\/api\/agents\/Nebula\/avatar\/meta/,
  /http 404: .*\/agents\/Nebula\/agent\.json/,
  /console\.error: .*\/agents\/Nebula\/agent\.json/,
  /requestfailed: .*\/agents\/Nebula\/agent\.json/,
  /http 404: .*\/api\/canvas-tabs/,
  /console\.error: .*\/api\/canvas-tabs/,
  /http 404: .*\/api\/portal\/status/,
  /console\.error: .*\/api\/portal\/status/,
  // nf-reach 可达性探针在页面导航/刷新即 abort（ERR_ABORTED，探针自带
  // AbortSignal——同上 abort by design 族；127.0.0.1/localhost 双拼同放）。
  /requestfailed: .*\/api\/nf-reach net::ERR_ABORTED/,
];
const realErrors = consoleErrors.filter((e) => !whitelist.some((re) => re.test(e)));
check('console clean (whitelisted boot noise excluded)', realErrors.length === 0, realErrors.slice(0, 4).join(' | ').slice(0, 300));

await browser.close();

console.log('---');
console.log(`[shots] ${SHOTS}`);
console.log(`[home] ${HOME}${CLEAN ? ' (cleaned)' : ' (kept)'}`);
if (failed > 0) { await cleanup(); process.exit(1); }
await cleanup();
process.exit(0);
