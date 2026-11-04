#!/usr/bin/env node
// todo-persist-live.mjs — persist2 节点：**五项真落库 + 刷新存活 + 跨端一致** 的隔离实例实测。
//
// 口径（本节点任务书）：
//   1 完成 / 2 取消完成 / 3 新建（带重要度）/ 4 删除 / 5 改重要度 ⇒ 真源落盘；
//   6 跨端一致（两个独立 browser context × 两视口 1280×800 / 375×812，A 端改 ⇒ B 端 reload 可见）。
//
// 真源口径：本仓待办**没有 REST 端点**（HTTP 面只有 /ws 等）⇒ 真源 = 隔离实例的
//   `ScheduledTaskStore` 落盘文件 `<HOME>/scheduled-tasks/<sessionId>.json`
//   （单行 JSON 数组，`os.write.over` 整文件覆写）；完成痕迹 = `<HOME>/checkin-records/done-records.jsonl`。
//   本脚本对两者做**直查**（读原文 + JSON 解析 + 落证据），并同时读页面内的产品活体
//   （`todoStore` 模块读口）与 DOM 行序，三方交叉。
//
// 载体：隔离 NEBFLOW_HOME（tmpdir 下 qa-*）+ 隔离端口（8119）+ 隔离实例（本 worktree 的
//   assembly jar，--home/--port/--no-browser/--foreground）+ stub LLM。
//   **零 6138、零 sbt（jar 已构建）、绝不 kill/signal 任何既有进程**（只回收本脚本 spawn 的子进程）。
//
// Run: node reports-evidence/<batch>/persist2/todo-persist-live.mjs
// Env: GW_PORT=8119 MOCK_PORT=19002 EVID=<证据目录> HOME_DIR=<隔离 home> CLEAN=1 拆夹具

import { spawn, execSync } from 'node:child_process';
import http from 'node:http';
import { readFileSync, writeFileSync, mkdirSync, cpSync, rmSync, existsSync, readdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { homedir, tmpdir } from 'node:os';
import { setTimeout as sleep } from 'node:timers/promises';
import { chromium } from '/opt/homebrew/lib/node_modules/playwright/index.mjs';
import { guardFixtureHome, safeRm, assertCleanable } from '../../../scripts/lib/delguard.mjs';
import { guardWrites } from '../../../scripts/lib/writeguard.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));           // …/persist2
const REPO = dirname(dirname(dirname(HERE)));                   // worktree 根（persist2 → batch → reports-evidence → 根）
const EVID = process.env.EVID || HERE;
const GW_PORT = Number(process.env.GW_PORT || 8119);
const MOCK_PORT = Number(process.env.MOCK_PORT || 19002);
const BASE = `http://127.0.0.1:${GW_PORT}`;
const HOME = process.env.HOME_DIR || join(tmpdir(), 'qa-todo-persist2');
const SHOTS = join(EVID, 'shots');
const CLEAN = process.env.CLEAN === '1' || process.env.NB_CLEAN === '1';
// 宿主实载 agent 定义（只读拷贝；数据根已迁 `~/.stand`，L2 名 = Nebula）
const AGENT_SRC_ROOT = process.env.AGENT_SRC_ROOT || join(homedir(), '.stand', 'agents');
const AGENT_NAMES = ['Nebula', 'Manager'];

mkdirSync(SHOTS, { recursive: true });
guardWrites([SHOTS], { repoRoot: REPO, label: 'todo-persist-live' });
guardFixtureHome(HOME, { label: 'todo-persist-live' });

let failed = 0;
const results = [];
function check(name, ok, extra = '') {
  if (!ok) failed++;
  results.push({ name, ok, extra });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
}
function section(t) { console.log(`\n=== ${t} ===`); }
function ev(file, content) {
  mkdirSync(EVID, { recursive: true });
  writeFileSync(join(EVID, file), content);
}

// ── 子进程登记 + 清理（只回收本脚本 spawn 的）──────────────────────────────
const children = [];
let cleaned = false;
let mockServer = null;
async function cleanup() {
  if (cleaned) return;
  cleaned = true;
  try { mockServer?.close(); } catch { /* gone */ }
  for (const c of children) {
    try { if (c.pid) process.kill(-c.pid, 'SIGKILL'); } catch { /* gone */ }
  }
  if (CLEAN) {
    try { safeRm(HOME, { label: 'todo-persist-live/cleanup' }); console.log('[cleanup] fixture home removed'); }
    catch (e) { console.log(`[cleanup] keep home (${e.message})`); }
  } else {
    console.log(`[cleanup] kept fixture home: ${HOME}`);
  }
}
for (const sig of ['SIGINT', 'SIGTERM', 'uncaughtException', 'unhandledRejection']) {
  process.on(sig, async (e) => { if (e) console.error(e); await cleanup(); process.exit(1); });
}

// ── pre-flight：端口空；6138（作者生产实例）监听集快照 ──────────────────────
{
  for (const port of [GW_PORT, MOCK_PORT]) {
    const busy = execSync(`lsof -nP -iTCP:${port} -sTCP:LISTEN 2>/dev/null || true`, { shell: '/bin/bash' }).toString().trim();
    if (busy) { console.error(`port ${port} occupied — abort\n${busy}`); process.exit(1); }
  }
}
const P6138_BEFORE = execSync('lsof -nP -iTCP:6138 -sTCP:LISTEN -t 2>/dev/null | sort | tr "\\n" " "', { shell: '/bin/bash' }).toString().trim();
console.log(`[pre] 6138 listeners: [${P6138_BEFORE}]`);

// ── stub LLM（隔离实例需要 provider 配置；本脚本不驱动 agent turn）──────────
function startMock() {
  const server = http.createServer((req, res) => {
    if (req.method === 'POST' && /\/v1\/chat\/completions$|\/chat\/completions$/.test(req.url || '')) {
      let raw = '';
      req.on('data', (d) => (raw += d));
      req.on('end', () => {
        res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-cache', connection: 'keep-alive' });
        res.end('data: ' + JSON.stringify({
          id: 'chatcmpl-stub', object: 'chat.completion.chunk', created: Math.floor(Date.now() / 1000),
          model: 'mock-1',
          choices: [{ index: 0, delta: { role: 'assistant', content: 'ok' }, finish_reason: null }],
        }) + '\n\ndata: ' + JSON.stringify({
          id: 'chatcmpl-stub', object: 'chat.completion.chunk', created: Math.floor(Date.now() / 1000),
          model: 'mock-1', choices: [{ index: 0, delta: {}, finish_reason: 'stop' }],
        }) + '\n\ndata: [DONE]\n\n');
      });
      return;
    }
    res.writeHead(404).end();
  });
  return new Promise((resolve) => server.listen(MOCK_PORT, '127.0.0.1', () => resolve(server)));
}

// ── fixture home ──────────────────────────────────────────────────────────
function buildFixture() {
  assertCleanable(HOME, { label: 'todo-persist-live/buildFixture' });
  rmSync(HOME, { recursive: true, force: true });
  mkdirSync(HOME, { recursive: true });
  if (existsSync(AGENT_SRC_ROOT)) {
    for (const name of AGENT_NAMES) {
      const src = join(AGENT_SRC_ROOT, name);
      if (existsSync(src)) cpSync(src, join(HOME, 'agents', name), { recursive: true });
    }
  } else {
    console.log(`[warn] agent source root missing: ${AGENT_SRC_ROOT}`);
  }
  writeFileSync(join(HOME, 'nebflow.json'), JSON.stringify({
    llm: { providers: { mock: { baseUrl: `http://127.0.0.1:${MOCK_PORT}/v1/`, apiKey: 'sk-e2e-stub', protocol: 'openai', models: [{ id: 'mock-1', maxTokens: 8192, contextWindow: 128000 }] } } },
  }));
  writeFileSync(join(HOME, 'onboarding.json'), JSON.stringify({ state: 'skipped' }));
}

// ── 隔离实例 ──────────────────────────────────────────────────────────────
function findJar() {
  const dir = join(REPO, 'target', 'scala-3.5.2');
  const jars = readdirSync(dir).filter((f) => f.startsWith('stand-assembly-') && f.endsWith('.jar'));
  if (!jars.length) throw new Error(`assembly jar not found in ${dir} — run sbt assembly`);
  return join(dir, jars.sort().pop());
}
function startGateway() {
  const jar = findJar();
  console.log(`[phase] jar: ${jar}`);
  const child = spawn('java', [
    '--add-opens', 'java.base/java.lang=ALL-UNNAMED',
    '-cp', jar, 'nebflow.Main',
    '--home', HOME, '--port', String(GW_PORT), '--no-browser', '--foreground', 'start',
  ], {
    detached: true, stdio: ['ignore', 'pipe', 'pipe'],
    env: {
      ...process.env,
      NEBFLOW_GATEWAY_PORT: String(GW_PORT),
      NEBFLOW_HOME: HOME,
      // 跨端腿需要两个控制台同时在线；产品「控制台单实例接管」是**正交的另一种**
      // 产品行为（后连者顶掉先连者 ⇒ 先连者盖接管模态，指针事件被 overlay 拦）。
      // 隔离实例用总闸 env 关掉它（Branding.env 双前缀：STAND_ / NEBFLOW_；
      // 生产 6138 不受影响，脚本未触碰它）。ConsoleTakeover.scala:79-81 载明 off = 全 no-op。
      STAND_CONSOLE_SINGLE_INSTANCE: 'off',
      NEBFLOW_CONSOLE_SINGLE_INSTANCE: 'off',
    },
  });
  children.push(child);
  const logs = [];
  const onData = (tag) => (d) => {
    const s = String(d);
    logs.push(`${tag}${s}`);
    if (/error|Exception|Listen|listen|started|Started|Gateway/i.test(s)) console.log(`[gw] ${s.trim().slice(0, 200)}`);
  };
  child.stdout.on('data', onData('[out] '));
  child.stderr.on('data', onData('[err] '));
  child.on('exit', (code) => console.log(`[gw] exited code=${code}`));
  return { child, logs };
}
async function waitGateway(timeoutMs = 180000) {
  const t0 = Date.now();
  while (Date.now() - t0 < timeoutMs) {
    try { const r = await fetch(BASE); if (r.status < 500) return true; } catch { /* not yet */ }
    await sleep(1500);
  }
  return false;
}

// ── 真源直查（现实测的落盘文件）───────────────────────────────────────────
const STORE_DIR = () => join(HOME, 'scheduled-tasks');
const DONE_FILE = () => join(HOME, 'checkin-records', 'done-records.jsonl');

function storeFilePaths() {
  const d = STORE_DIR();
  if (!existsSync(d)) return [];
  return readdirSync(d).filter((f) => f.endsWith('.json')).map((f) => join(d, f));
}
function storeRaw() {
  return storeFilePaths().map((p) => ({ path: p, raw: readFileSync(p, 'utf8') }));
}
function storeRows() {
  const rows = [];
  for (const { path, raw } of storeRaw()) {
    let arr = [];
    try { arr = JSON.parse(raw); } catch { continue; }
    if (!Array.isArray(arr)) continue;
    for (const r of arr) rows.push({ ...r, __file: path });
  }
  return rows;
}
function doneLines() {
  const p = DONE_FILE();
  if (!existsSync(p)) return [];
  return readFileSync(p, 'utf8').split('\n').filter(Boolean);
}
/** 落证据：真源原文 + 摘要（绝对路径 + 原始片段）。 */
function snapshotStore(tag, note = '') {
  const files = storeRaw();
  const head = [`# 真源快照 ${tag} @ ${new Date().toISOString()}`, `# 目录: ${STORE_DIR()}`, note, ''];
  if (!files.length) head.push(`（目录下无 .json 文件：${STORE_DIR()} 不存在或为空）`);
  for (const f of files) {
    head.push(`## 文件: ${f.path}`, `## 长度: ${f.raw.length} 字符`, '## 原文:', f.raw, '');
  }
  const done = doneLines();
  head.push(`## DoneRecord 文件: ${DONE_FILE()}（${done.length} 行）`, ...done, '');
  return head.join('\n');
}
async function waitStoreRows(pred, { timeout = 20000, interval = 250, label = '' } = {}) {
  const t0 = Date.now();
  let last = [];
  while (Date.now() - t0 < timeout) {
    last = storeRows();
    if (pred(last)) return last;
    await sleep(interval);
  }
  console.log(`  [timeout] waitStoreRows ${label} after ${timeout}ms — rows=${JSON.stringify(last.map((r) => ({ id: r.id, content: r.content, priority: r.priority, completedAt: r.completedAt })))}`);
  return last;
}

/** 应用就绪判据：state.js 有活动会话 + WS 已连（比 #search-btn 可见更稳——该钮在
 *  某些实例状态下被 nb-header-hide 收起）。 */
async function waitAppReady(page, timeout = 60000) {
  await page.waitForFunction(async () => {
    try {
      const s = (await import('/js/state.js')).default;
      return !!s.activeSessionId && !!s.ws && s.ws.readyState === 1;
    } catch { return false; }
  }, null, { timeout });
}

// ── 页面活体读口（产品模块，不经夹具）──────────────────────────────────────
async function liveState(page) {
  return page.evaluate(async () => {
    const m = await import('/js/todoStore.js');
    const s = m.todoStore;
    return {
      dataSource: s.dataSource,
      openCount: s.openCount(),
      visible: s.visibleItems().map((i) => ({ id: i.id, title: i.title, priority: i.priority, completed: i.completed })),
      completed: s.completedItems().map((i) => ({ id: i.id, title: i.title, completedAt: i.completedAt })),
    };
  });
}
async function reloadWithArchive(page) {
  // 刷新 = 真 reload；已完成行走 opt-in 归档面读口（后端默认不返回已完成行）
  await page.reload({ waitUntil: 'domcontentloaded' });
  await waitAppReady(page);
  await page.waitForTimeout(1200);
  await page.evaluate(async () => {
    const m = await import('/js/todoStore.js');
    m.todoStore.refresh({ includeCompleted: true });
  });
  await page.waitForTimeout(1500);
}
async function waitCapsule(page) {
  await page.waitForSelector('#task-list.has-tasks .tfl-capsule', { timeout: 40000 });
}
/** 兜底：若仍有接管/确认类 overlay 拦指针（本实例已关总闸，正常不该出现），
 *  点「重新接管」收敛一次，再继续。 */
async function dismissTakeover(page) {
  const live = await page.locator('#modal-overlay.takeover-live').count();
  if (!live) return false;
  const btn = page.locator('#takeover-retake');
  if (await btn.count()) { await btn.click().catch(() => {}); }
  await page.waitForTimeout(2500);
  return true;
}
async function openPanel(page) {
  await dismissTakeover(page);
  // 展开态下胶囊自身是隐藏的（aria-expanded=true）⇒ 已开就不要再等胶囊
  if (await page.locator('.tfl-card[data-state="open"]').count()) { await page.waitForTimeout(200); return; }
  await page.waitForSelector('.tfl-capsule', { state: 'visible', timeout: 40000 });
  await page.locator('.tfl-capsule').click();
  await page.waitForSelector('.tfl-card[data-state="open"]', { timeout: 15000 });
  await page.waitForTimeout(900); // spring 落地
}
async function rowTitles(page) {
  return page.$$eval('.tfl-row .tfl-row-title', (els) => els.map((e) => e.textContent));
}
async function createTodo(page, title, prio) {
  await openPanel(page);
  await page.locator('.tfl-fab-add').click();
  await page.waitForSelector('.tfl-create-row', { timeout: 10000 });
  await page.waitForTimeout(150);
  await page.locator('.tfl-create-panel .tfl-edit-prio-btn').nth(prio).click();
  const input = page.locator('.tfl-create-row .tfl-row-title-input');
  await input.fill(title);
  await input.press('Enter');
  await page.waitForTimeout(400);
}
function rowOf(page, title) {
  return page.locator('.tfl-row', { has: page.locator(`.tfl-row-title:text-is("${title}")`) }).first();
}
async function setPriority(page, title, prioIdx) {
  await openPanel(page);
  const row = rowOf(page, title);
  await row.locator('.tfl-row-main').click();
  await page.waitForSelector('.tfl-row-editing', { timeout: 10000 });
  await page.waitForTimeout(200);
  await page.locator('.tfl-row-editing .tfl-edit-prio-btn').nth(prioIdx).click();
  const input = page.locator('.tfl-row-editing .tfl-row-title-input');
  await input.press('Enter'); // saveEditing
  await page.waitForTimeout(500);
}

// ── 主链 ──────────────────────────────────────────────────────────────────
mockServer = await startMock();
console.log(`[phase] stub LLM on :${MOCK_PORT}`);
buildFixture();
console.log(`[phase] fixture home: ${HOME}`);
const gw = startGateway();
if (!(await waitGateway())) {
  console.log('FAIL  isolated gateway never came up');
  ev('00_gateway_boot.log', gw.logs.join(''));
  await cleanup();
  process.exit(1);
}
check('隔离实例起来（非 6138 端口）', true, BASE);
{
  const gateLine = gw.logs.join('').split('\n').filter((l) => /console-takeover/.test(l)).join('\n');
  ev('00_gateway_boot_console_takeover.txt', (gateLine || '(boot 日志无 console-takeover 行)') +
    '\n# 隔离实例 env：STAND_CONSOLE_SINGLE_INSTANCE=off / NEBFLOW_CONSOLE_SINGLE_INSTANCE=off（跨端腿需两控制台并存）');
}
{
  const after = execSync('lsof -nP -iTCP:6138 -sTCP:LISTEN -t 2>/dev/null | sort | tr "\\n" " "', { shell: '/bin/bash' }).toString().trim();
  check('6138 作者生产实例监听集未受影响', after === P6138_BEFORE, `before=[${P6138_BEFORE}] after=[${after}]`);
}

const TOKEN = JSON.parse(readFileSync(join(HOME, 'auth.json'), 'utf8'));
async function api(path) {
  const res = await fetch(`${BASE}/api${path}`, { headers: { authorization: `Bearer ${TOKEN}` } });
  return { status: res.status, json: await res.json().catch(() => null) };
}
const sessList = await api('/sessions');
const sessions = sessList.json?.sessions || sessList.json || [];
const root = sessions.find((s) => s.agentName === 'Nebula') || sessions[0];
const SID = root?.id;
check('隔离实例存在可渲染会话（胶囊挂载门 sessionShowsNodeEntries 通过）', !!SID, `sessionId=${SID} agent=${root?.agentName}`);

// ── 两个独立 browser context × 两视口 ─────────────────────────────────────
const browser = await chromium.launch();
const errors = { desktop: [], mobile: [] };
const pageErrors = { desktop: [], mobile: [] };
const httpFails = { desktop: [], mobile: [] };
function wire(page, tag) {
  page.on('pageerror', (e) => { errors[tag].push(`pageerror: ${e.message}`); pageErrors[tag].push(`pageerror: ${e.message}`); });
  page.on('console', (m) => { if (m.type() === 'error') errors[tag].push(`console.error: ${m.text()}`); });
  page.on('response', (r) => {
    if (r.status() >= 400 && !/favicon/.test(r.url())) { errors[tag].push(`http ${r.status()} ${r.url()}`); httpFails[tag].push(`http ${r.status()} ${r.url()}`); }
  });
}
async function newContext(viewport, tag) {
  const ctx = await browser.newContext({ baseURL: BASE, colorScheme: 'light', viewport, locale: 'zh-CN' });
  await ctx.addInitScript((tok) => {
    localStorage.setItem('nebflow_token', tok);
    localStorage.setItem('nebflow_locale', 'zh-CN');
    window.__wsFrames = [];
    // 跨 reload 累计（A 端中途会 reload 好几次；只存内存会丢早期写帧）
    const KEY = '__nf_ws_counts';
    const bump = (k) => {
      try {
        const c = JSON.parse(localStorage.getItem(KEY) || '{}');
        c[k] = (c[k] || 0) + 1;
        localStorage.setItem(KEY, JSON.stringify(c));
      } catch { /* quota/private */ }
    };
    const Orig = window.WebSocket;
    window.WebSocket = class extends Orig {
      constructor(...args) {
        super(...args);
        this.addEventListener('message', (ev) => {
          try { const m = JSON.parse(ev.data); window.__wsFrames.push(m.type); bump('RECV:' + m.type); } catch { /* non-JSON */ }
        });
        const send = this.send.bind(this);
        this.send = (data) => {
          try { const t = JSON.parse(data).type; window.__wsFrames.push('SEND:' + t); bump('SEND:' + t); } catch { /* json */ }
          return send(data);
        };
      }
    };
  }, TOKEN);
  const page = await ctx.newPage();
  wire(page, tag);
  const mint = await fetch(`${BASE}/api/auth/console-ticket`, { method: 'POST', headers: { authorization: `Bearer ${TOKEN}` } });
  const { ticket } = await mint.json();
  await page.goto(`${BASE}/?ticket=${encodeURIComponent(ticket)}`, { waitUntil: 'domcontentloaded' });
  await waitAppReady(page);
  const ob = await page.waitForSelector('.onboarding-overlay', { state: 'attached', timeout: 3000 }).then(() => true).catch(() => false);
  if (ob) {
    const skip = await page.$('#ob-skip');
    if (skip) await skip.click(); else await page.click('#ob-no').catch(() => {});
    await page.waitForSelector('.onboarding-overlay', { state: 'detached', timeout: 5000 }).catch(() => {});
  }
  await page.waitForTimeout(1500);
  return { ctx, page };
}

const A = await newContext({ width: 1280, height: 800 }, 'desktop');   // A 端 = 桌面
const B = await newContext({ width: 375, height: 812 }, 'mobile');     // B 端 = 手机
check('桌面 context 1280×800 起来', true, BASE);
check('手机 context 375×812 起来', true, BASE);
{
  const bodyCls = await B.page.evaluate(() => document.body.className);
  check('手机端进入 mobile-layer（375 视口）', /mobile-layer/.test(bodyCls), `body.class="${bodyCls}"`);
  const capVisible = await B.page.evaluate(() => {
    const el = document.querySelector('.tfl-capsule');
    if (!el) return null;
    const r = el.getBoundingClientRect();
    return { w: r.width, h: r.height, display: getComputedStyle(el).display };
  });
  check('手机端胶囊在场可见（跨端读面的载体）', !!capVisible && capVisible.w > 0 && capVisible.h > 0, JSON.stringify(capVisible));
}
const before = await liveState(A.page);
check('A 端 dataSource = backend（非夹具）', before.dataSource === 'backend', JSON.stringify({ dataSource: before.dataSource, openCount: before.openCount }));
ev('03_baseline_store.txt', snapshotStore('baseline', `sessionId=${SID}；孤立实例初始真源`));
ev('03_baseline_live.txt', JSON.stringify({ sessionId: SID, liveState: before, storeRows: storeRows() }, null, 2));
if (process.env.SMOKE === '1') {
  console.log(`\n[SMOKE] boot+read 面自检完成 — PASS ${results.length - failed} / FAIL ${failed}`);
  await browser.close();
  await cleanup();
  process.exit(failed ? 1 : 0);
}

// ══════════════════ ① 新建（带重要度）════════════════════════════════════
section('① 新建（带重要度 3）⇒ 真源出现该行且 priority 正确，刷新后仍在');
const T_NEW = 'PERSIST-①-新建高重要度';
await createTodo(A.page, T_NEW, 3);
let rows = await waitStoreRows((rs) => rs.some((r) => r.content === T_NEW && r.kind === 'todo'), { label: 'new' });
const newRow = rows.find((r) => r.content === T_NEW);
check('①-1 真源落盘出现该行（scheduled-tasks/*.json 直查）', !!newRow, `file=${newRow?.__file}`);
check('①-2 真源 priority = 3（新建择重要度真落库）', newRow?.priority === 3, `priority=${newRow?.priority}`);
check('①-3 真源 kind = todo 且未完成', newRow?.kind === 'todo' && (newRow?.completedAt === null || newRow?.completedAt === undefined),
  `kind=${newRow?.kind} completedAt=${newRow?.completedAt}`);
ev('10_new_store.txt', snapshotStore('① 新建后', `target content="${T_NEW}"；id=${newRow?.id}`));
await A.page.screenshot({ path: join(SHOTS, '10_desktop_new_created.png') });
const NEW_ID = newRow?.id;

await reloadWithArchive(A.page);
{
  const st = await liveState(A.page);
  const srows = storeRows();
  const persisted = srows.find((r) => r.id === NEW_ID);
  check('①-4 刷新后真源该行仍在且 priority 不变', !!persisted && persisted.priority === 3, `id=${NEW_ID} priority=${persisted?.priority}`);
  check('①-5 刷新后页面活体可见该行（未完成面）', st.visible.some((i) => i.title === T_NEW), JSON.stringify(st.visible.map((i) => i.title)));
  await openPanel(A.page);
  check('①-6 刷新后 DOM 行序含该行', (await rowTitles(A.page)).includes(T_NEW), JSON.stringify(await rowTitles(A.page)));
  ev('11_new_after_reload.txt', JSON.stringify({ storeRows: srows.map((r) => ({ id: r.id, content: r.content, priority: r.priority, completedAt: r.completedAt, __file: r.__file })), live: st, domTitles: await rowTitles(A.page) }, null, 2));
  await A.page.screenshot({ path: join(SHOTS, '11_desktop_new_after_reload.png') });
}

// ══════════════════ ② 完成 ══════════════════════════════════════════════
section('② 完成 ⇒ 真源 completedAt 置位 + DoneRecord 落盘；刷新后仍在已完成态');
const doneBefore = doneLines().length;
await openPanel(A.page);
{
  const row = rowOf(A.page, T_NEW);
  await row.locator('.tfl-complete').click();
  await A.page.waitForTimeout(1400); // 三段动画 + 帧
}
rows = await waitStoreRows((rs) => (rs.find((r) => r.id === NEW_ID)?.completedAt ?? null) != null, { label: 'complete' });
{
  const r = rows.find((x) => x.id === NEW_ID);
  check('②-1 真源 completedAt 置位（软删落盘）', r && typeof r.completedAt === 'number' && r.completedAt > 0, `completedAt=${r?.completedAt}`);
  const dl = doneLines();
  const last = dl[dl.length - 1] ? JSON.parse(dl[dl.length - 1]) : null;
  check('②-2 DoneRecord 落盘追加一行（checkin-records/done-records.jsonl）', dl.length === doneBefore + 1 && last?.taskId === NEW_ID,
    `lines ${doneBefore}→${dl.length} lastTaskId=${last?.taskId} via=${last?.via}`);
  ev('20_complete_store.txt', snapshotStore('② 完成后', `id=${NEW_ID}`));
  ev('20_complete_done_records.txt', `# ${DONE_FILE()}\n` + dl.join('\n'));
  await A.page.screenshot({ path: join(SHOTS, '20_desktop_completed.png') });
}
await reloadWithArchive(A.page);
{
  const st = await liveState(A.page);
  const srows = storeRows();
  const r = srows.find((x) => x.id === NEW_ID);
  check('②-3 刷新后真源 completedAt 仍在（落库不是内存态）', r && typeof r.completedAt === 'number', `completedAt=${r?.completedAt}`);
  check('②-4 刷新后未完成面不再出现该行（收件箱默认面）', !st.visible.some((i) => i.title === T_NEW), JSON.stringify(st.visible.map((i) => i.title)));
  check('②-5 刷新后已完成面（includeCompleted 归档读口）仍见该行 completed=true', st.completed.some((i) => i.title === T_NEW), JSON.stringify(st.completed.map((i) => i.title)));
  await openPanel(A.page);
  check('②-6 刷新后 DOM 未完成行序不含该行', !(await rowTitles(A.page)).includes(T_NEW), JSON.stringify(await rowTitles(A.page)));
  ev('21_complete_after_reload.txt', JSON.stringify({ store: srows.map((x) => ({ id: x.id, content: x.content, completedAt: x.completedAt })), live: st, domTitles: await rowTitles(A.page) }, null, 2));
  await A.page.screenshot({ path: join(SHOTS, '21_desktop_completed_after_reload.png') });
}

// ══════════════════ ③ 取消完成 ═══════════════════════════════════════════
section('③ 取消完成 ⇒ 真源 completedAt 清空，刷新回到未完成');
const T_UNDO = 'PERSIST-③-取消完成';
await createTodo(A.page, T_UNDO, 1);
rows = await waitStoreRows((rs) => rs.some((r) => r.content === T_UNDO), { label: 'undo-create' });
const UNDO_ID = rows.find((r) => r.content === T_UNDO)?.id;
{
  await openPanel(A.page);
  const row = rowOf(A.page, T_UNDO);
  await row.locator('.tfl-complete').click();
  await A.page.waitForTimeout(1400);
  const midRows = await waitStoreRows((rs) => (rs.find((x) => x.id === UNDO_ID)?.completedAt ?? null) != null, { label: 'undo-mid' });
  check('③-0 前置：完成帧已落盘（completedAt 置位）', (midRows.find((x) => x.id === UNDO_ID)?.completedAt ?? null) != null, `id=${UNDO_ID}`);
  // 撤回条（完成后 6s 窗口内的唯一产品路径）
  await A.page.waitForSelector('.tfl-undo', { state: 'visible', timeout: 5000 });
  await A.page.locator('.tfl-undo-btn').click();
  await A.page.waitForTimeout(800);
  rows = await waitStoreRows((rs) => {
    const r = rs.find((x) => x.id === UNDO_ID);
    return r && (r.completedAt === null || r.completedAt === undefined);
  }, { label: 'uncomplete' });
  const r = rows.find((x) => x.id === UNDO_ID);
  check('③-1 真源 completedAt 清空', r && (r.completedAt === null || r.completedAt === undefined), `completedAt=${r?.completedAt}`);
  ev('30_uncomplete_store.txt', snapshotStore('③ 取消完成后', `id=${UNDO_ID}`));
  await A.page.screenshot({ path: join(SHOTS, '30_desktop_uncompleted.png') });
}
await reloadWithArchive(A.page);
{
  const st = await liveState(A.page);
  const r = storeRows().find((x) => x.id === UNDO_ID);
  check('③-2 刷新后真源仍是未完成（completedAt 空）', r && (r.completedAt == null), `completedAt=${r?.completedAt}`);
  check('③-3 刷新后该行回到未完成可见面', st.visible.some((i) => i.title === T_UNDO), JSON.stringify(st.visible.map((i) => i.title)));
  await openPanel(A.page);
  check('③-4 刷新后 DOM 行序含该行', (await rowTitles(A.page)).includes(T_UNDO), JSON.stringify(await rowTitles(A.page)));
  ev('31_uncomplete_after_reload.txt', JSON.stringify({ store: storeRows().map((x) => ({ id: x.id, content: x.content, completedAt: x.completedAt })), live: st, domTitles: await rowTitles(A.page) }, null, 2));
}

// ══════════════════ ④ 删除 ═══════════════════════════════════════════════
section('④ 删除 ⇒ 真源该行物理消失，刷新后不复现');
await openPanel(A.page);
{
  const row = rowOf(A.page, T_UNDO);
  await row.locator('.tfl-del').click();
  await A.page.waitForTimeout(800);
}
rows = await waitStoreRows((rs) => !rs.some((r) => r.id === UNDO_ID), { label: 'delete' });
check('④-1 真源该行物理消失（文件内不存在该 id）', !rows.some((r) => r.id === UNDO_ID), `id=${UNDO_ID} remaining=${JSON.stringify(rows.map((r) => r.id))}`);
ev('40_delete_store.txt', snapshotStore('④ 删除后', `deleted id=${UNDO_ID}`));
await A.page.screenshot({ path: join(SHOTS, '40_desktop_deleted.png') });
await reloadWithArchive(A.page);
{
  const st = await liveState(A.page);
  check('④-2 刷新后真源仍无该行', !storeRows().some((r) => r.id === UNDO_ID));
  check('④-3 刷新后页面活体不复现该行', !st.visible.some((i) => i.title === T_UNDO) && !st.completed.some((i) => i.title === T_UNDO), JSON.stringify(st));
  await openPanel(A.page);
  check('④-4 刷新后 DOM 行序不含该行', !(await rowTitles(A.page)).includes(T_UNDO), JSON.stringify(await rowTitles(A.page)));
  ev('41_delete_after_reload.txt', JSON.stringify({ store: storeRows().map((x) => ({ id: x.id, content: x.content })), live: st, domTitles: await rowTitles(A.page) }, null, 2));
}

// ══════════════════ ⑤ 改重要度（含排序前后 DOM 顺序）═════════════════════
section('⑤ 改重要度 ⇒ 真源 priority 变化，刷新后排序位置随之变化');
const T_LOW = 'PERSIST-⑤-低重要度';
const T_HIGH = 'PERSIST-⑤-高重要度';
await createTodo(A.page, T_LOW, 1);
await waitStoreRows((rs) => rs.some((r) => r.content === T_LOW), { label: 'prio-low' });
await createTodo(A.page, T_HIGH, 3);
await waitStoreRows((rs) => rs.some((r) => r.content === T_HIGH && r.priority === 3), { label: 'prio-high' });
const HIGH_ID = storeRows().find((r) => r.content === T_HIGH)?.id;
const LOW_ID = storeRows().find((r) => r.content === T_LOW)?.id;
await openPanel(A.page);
const orderBefore = await rowTitles(A.page);
check('⑤-0 改前排序：高重要度(3) 排在低重要度(1) 之前', orderBefore.indexOf(T_HIGH) < orderBefore.indexOf(T_LOW), JSON.stringify(orderBefore));
ev('50_priority_order_before.txt', JSON.stringify({ order: orderBefore, store: storeRows().map((x) => ({ id: x.id, content: x.content, priority: x.priority })) }, null, 2));

await setPriority(A.page, T_HIGH, 0); // 高(3) → 无(0) ⇒ 应让位到低(1) 之后
rows = await waitStoreRows((rs) => rs.find((r) => r.id === HIGH_ID)?.priority === 0, { label: 'priority-change' });
check('⑤-1 真源 priority 由 3 变 0（updateScheduledTask 落库）', rows.find((r) => r.id === HIGH_ID)?.priority === 0, `priority=${rows.find((r) => r.id === HIGH_ID)?.priority}`);
await openPanel(A.page);
const orderAfter = await rowTitles(A.page);
check('⑤-2 排序位置随之变化：低重要度(1) 反超高重要度(0)', orderAfter.indexOf(T_LOW) < orderAfter.indexOf(T_HIGH),
  `before=${JSON.stringify(orderBefore)} after=${JSON.stringify(orderAfter)}`);
ev('51_priority_order_after.txt', JSON.stringify({ order: orderAfter, store: storeRows().map((x) => ({ id: x.id, content: x.content, priority: x.priority })) }, null, 2));
await A.page.screenshot({ path: join(SHOTS, '51_desktop_priority_changed.png') });

await reloadWithArchive(A.page);
{
  const r = storeRows().find((x) => x.id === HIGH_ID);
  check('⑤-3 刷新后真源 priority 仍为 0', r?.priority === 0, `priority=${r?.priority}`);
  await openPanel(A.page);
  const orderReload = await rowTitles(A.page);
  check('⑤-4 刷新后 DOM 排序仍为改后顺序', orderReload.indexOf(T_LOW) < orderReload.indexOf(T_HIGH), JSON.stringify(orderReload));
  ev('52_priority_after_reload.txt', JSON.stringify({ order: orderReload, store: storeRows().map((x) => ({ id: x.id, content: x.content, priority: x.priority })) }, null, 2));
}

// ══════════════════ ⑥ 跨端一致（B 端 mobile 375×812）═════════════════════
section('⑥ 跨端一致：A 端（桌面）改 ⇒ B 端（手机）reload 可见');
// B 端起点读数
await reloadWithArchive(B.page);
{
  const stB = await liveState(B.page);
  check('⑥-0 B 端 reload 后读同一真源（dataSource=backend，含 A 端此前写入）',
    stB.dataSource === 'backend' && stB.visible.some((i) => i.title === T_LOW),
    JSON.stringify({ dataSource: stB.dataSource, visible: stB.visible.map((i) => i.title) }));
  ev('60_cross_baseline.txt', JSON.stringify({ B: stB, store: storeRows().map((x) => ({ id: x.id, content: x.content, priority: x.priority, completedAt: x.completedAt })) }, null, 2));
}
// ⑥-a 新建跨端
const T_CROSS = 'PERSIST-⑥-跨端新建';
await createTodo(A.page, T_CROSS, 2);
await waitStoreRows((rs) => rs.some((r) => r.content === T_CROSS && r.priority === 2), { label: 'cross-new' });
const CROSS_ID = storeRows().find((r) => r.content === T_CROSS)?.id;
{
  const stA = await liveState(A.page);
  await reloadWithArchive(B.page);
  const stB = await liveState(B.page);
  check('⑥-a 新建跨端：A 端建（priority 2）⇒ B 端 reload 可见且 priority=2',
    stB.visible.some((i) => i.title === T_CROSS && i.priority === 2),
    `A=${JSON.stringify(stA.visible.filter((i) => i.title === T_CROSS))} B=${JSON.stringify(stB.visible.filter((i) => i.title === T_CROSS))}`);
  ev('61_cross_create.txt', JSON.stringify({ A: stA.visible, B: stB.visible, store: storeRows().map((x) => ({ id: x.id, content: x.content, priority: x.priority })) }, null, 2));
  await B.page.screenshot({ path: join(SHOTS, '61_mobile_sees_new.png') });
}
// ⑥-b 完成跨端
await openPanel(A.page);
{
  const row = rowOf(A.page, T_CROSS);
  await row.locator('.tfl-complete').click();
  await A.page.waitForTimeout(1400);
}
await waitStoreRows((rs) => (rs.find((r) => r.id === CROSS_ID)?.completedAt ?? null) != null, { label: 'cross-complete' });
{
  await reloadWithArchive(B.page);
  const stB = await liveState(B.page);
  const rB = storeRows().find((x) => x.id === CROSS_ID);
  check('⑥-b 完成跨端：A 端完成 ⇒ B 端 reload 后不在未完成面，归档面 completed=true',
    !stB.visible.some((i) => i.title === T_CROSS) && stB.completed.some((i) => i.title === T_CROSS),
    `B.visible=${JSON.stringify(stB.visible.map((i) => i.title))} B.completed=${JSON.stringify(stB.completed.map((i) => i.title))} completedAt=${rB?.completedAt}`);
  ev('62_cross_complete.txt', JSON.stringify({ B: stB, storeCompletedAt: rB?.completedAt }, null, 2));
  await B.page.screenshot({ path: join(SHOTS, '62_mobile_sees_complete.png') });
}
// ⑥-c 重要度跨端
await setPriority(A.page, T_LOW, 3); // 低(1) → 高(3)
await waitStoreRows((rs) => rs.find((r) => r.id === LOW_ID)?.priority === 3, { label: 'cross-priority' });
{
  const stA = await liveState(A.page);
  await reloadWithArchive(B.page);
  const stB = await liveState(B.page);
  const pA = stA.visible.find((i) => i.title === T_LOW)?.priority;
  const pB = stB.visible.find((i) => i.title === T_LOW)?.priority;
  check('⑥-c 重要度跨端：A 端 1→3 ⇒ B 端 reload 读到 priority=3', pB === 3 && pA === 3, `A.priority=${pA} B.priority=${pB}`);
  // 排序位置也随之跨端一致（3 应排在 0 之前）
  const orderB = stB.visible.map((i) => i.title);
  check('⑥-c2 排序跨端一致：B 端该行排在 priority=0 行之前',
    orderB.indexOf(T_LOW) < orderB.indexOf(T_HIGH), JSON.stringify(orderB));
  ev('63_cross_priority.txt', JSON.stringify({ A: stA.visible, B: stB.visible, store: storeRows().map((x) => ({ id: x.id, content: x.content, priority: x.priority })) }, null, 2));
  await B.page.screenshot({ path: join(SHOTS, '63_mobile_sees_priority.png') });
}

// ── 收尾：页面无错 + 真源总快照 + 6138 不变 ─────────────────────────────
section('收尾');
{
  const st = await liveState(A.page);
  // 判据分两层：① JS 异常（pageerror）必须为零；② HTTP 失败只允许**待办面之外**
  // 的既有端点噪声（隔离实例缺 portal/canvas 面）。原始全量清单落 91_page_errors.txt。
  const todoRelated = (u) => /\/ws|scheduled|\/api\/(sessions|auth)/i.test(u);
  check('总共：A 端零 JS 异常（pageerror）', pageErrors.desktop.length === 0, JSON.stringify(pageErrors.desktop.slice(0, 5)));
  check('总共：B 端零 JS 异常（pageerror）', pageErrors.mobile.length === 0, JSON.stringify(pageErrors.mobile.slice(0, 5)));
  check('总共：两端零待办面 HTTP 失败（/ws、scheduled*、/api/sessions|auth）',
    !httpFails.desktop.some(todoRelated) && !httpFails.mobile.some(todoRelated),
    JSON.stringify({ desktop: httpFails.desktop, mobile: httpFails.mobile }));
  check('总共：真源 dataSource 全程 = backend（无夹具/无内存 mock）', st.dataSource === 'backend' && before.dataSource === 'backend');
  ev('90_final_store_dump.txt', snapshotStore('收尾', `sessionId=${SID}`));
  ev('90_final_live_state.txt', JSON.stringify({ desktop: st, mobile: await liveState(B.page) }, null, 2));
  ev('91_page_errors.txt', JSON.stringify(errors, null, 2));
  const p6138 = execSync('lsof -nP -iTCP:6138 -sTCP:LISTEN -t 2>/dev/null | sort | tr "\\n" " "', { shell: '/bin/bash' }).toString().trim();
  check('收尾：6138 作者生产实例监听集仍不变（未触碰）', p6138 === P6138_BEFORE, `before=[${P6138_BEFORE}] after=[${p6138}]`);
  const frames = await A.page.evaluate(() => JSON.parse(localStorage.getItem('__nf_ws_counts') || '{}'));
  const writeFrames = ['SEND:createScheduledTask', 'SEND:completeTodo', 'SEND:uncompleteTodo', 'SEND:deleteScheduledTask', 'SEND:updateScheduledTask'];
  ev('92_ws_frames.txt', JSON.stringify(frames, null, 2));
  check('总共：五类写帧全在线上出现过（A 端帧计数）', writeFrames.every((f) => (frames[f] || 0) > 0), JSON.stringify(Object.fromEntries(writeFrames.map((f) => [f, frames[f] || 0]))));
}

writeFileSync(join(EVID, '99_summary.json'), JSON.stringify({ sessionId: SID, home: HOME, base: BASE, failed, total: results.length, results }, null, 2));
console.log(`\n==== PASS ${results.length - failed} / FAIL ${failed} ====`);
await browser.close();
await cleanup();
process.exit(failed ? 1 : 0);
