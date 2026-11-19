#!/usr/bin/env node
// _capture-real-canvas-evidence.mjs — 「真 canvas」证据取证（非闸；tests/_ 前缀不进
// playwright 收集面）。
//
// 作者令 2026-11-19（11:14）：「这几张截图为什么看着还是不是在真实的 canvas 里
// 查看的感觉？因为它肯定是在 canvas 里显示的」——夹具/对照页**不能当验收证据**。
// 本脚本起一个**真隔离实例**（真路由 / 真数据 / 真前端源码树），在真 canvas 容器内
// 打开真实 diff 审阅并截图；每条证据附「真 canvas 凭据」= 页面 URL + `.canvas-*`
// 容器选择器在场 + 数据来自真实来源。
//
// 两面各一（都在真 canvas 容器内）：
//   · `filechanges` —— **真 delegate 批次终态**：mock LLM 让 Nebula 真派发一次
//     Delegate（真工具、真会话、真终态）；引擎按 DelegateRegistry 的 cwd 对**真
//     git 仓库**跑只读 `git status --porcelain` + `git diff HEAD`（FileChangeReport
//     的 git 腿），随终态 ExternalEvent 下发 `fileChanges` 载荷；前端按真用户路径
//     （点产物卡 `[data-fc-batch]`）在 canvas 里开出 filechanges 页签。
//   · `askreview` —— **真 ask_edit 回合**：mock LLM 返回 ask_edit 工具调用，真引擎
//     真落盘 + 真 `askEditApplied` 帧，canvas 里自然长出 askreview 内联审阅页签。
//
// 隔离纪律（与 tests/harness/isolated-browser.mjs 同族）：
//   · 数据根 = $TMPDIR 下的 mkdtemp，经 --home + STAND_HOME + NEBFLOW_HOME 三重注入；
//   · 端口 = 随机空闲端口，拒绝 6138 / 8080 / 8097 / <1024；
//   · 只杀自己 spawn 的进程（--foreground）；结束即删临时根；
//   · 宿主控制台 :6138 零连接（真「模型端点」由本地 mock 顶替，零外部网络）。
//
// 用法：node tests/_capture-real-canvas-evidence.mjs <outDir>
//   <outDir> 缺省 = <repo>/evidence/diff-canvas-native-real（仓内 gitignored）；
//   本批证据的落址沿用旧惯例（仓外），调用时显式传
//   `/Users/kaiyu/stand-evidence/diff-canvas-native-20261119/real`。

import { chromium } from 'playwright';
import { spawn, spawnSync } from 'node:child_process';
import { createServer } from 'node:net';
import { createServer as createHttpServer } from 'node:http';
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync, cpSync, readdirSync, statSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, extname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = resolve(HERE, '..');
const OUT = resolve(process.argv[2] && !process.argv[2].startsWith('--') ? process.argv[2] : join(REPO, 'evidence', 'diff-canvas-native-real'));
const CP = readFileSync(join(REPO, 'target', 'iso-fullclasspath.txt'), 'utf8').trim();
const HOST_ROOT = join(process.env.HOME, '.stand');
const ISO_BASE = join(tmpdir(), 'stand-real-canvas');
const FORBIDDEN = new Set([6138, 8080, 8097, 3000, 5173]);
const FRAMES = join(OUT, '_frames');
mkdirSync(OUT, { recursive: true });
rmSync(FRAMES, { recursive: true, force: true });
mkdirSync(FRAMES, { recursive: true });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const log = (...a) => console.log('[real]', ...a);

async function freePort() {
  for (;;) {
    const s = createServer();
    await new Promise((ok, fail) => s.listen(0, '127.0.0.1', ok).once('error', fail));
    const p = s.address().port;
    await new Promise((ok) => s.close(ok));
    if (p >= 1024 && !FORBIDDEN.has(p)) return p;
  }
}
async function httpStatus(url) {
  try { return (await fetch(url, { signal: AbortSignal.timeout(2000) })).status; } catch { return 0; }
}
async function waitHealth(base, ms) {
  const end = Date.now() + ms;
  while (Date.now() < end) { if (await httpStatus(base + '/api/health') === 200) return true; await sleep(250); }
  return false;
}
function git(cwd, args) {
  const r = spawnSync('git', ['-c', 'user.email=evidence@local', '-c', 'user.name=evidence', ...args], { cwd, encoding: 'utf8' });
  if (r.status !== 0) throw new Error(`git ${args.join(' ')} failed: ${r.stderr || r.stdout}`);
  return (r.stdout || '').trim();
}

const PROJECT = 'real-canvas-demo';
// notes.md 种子（17 行）：两相 ask_edit 各改**行距 ≥6 行**的两处 ⇒ 组件
// （collapseUnchanged.minSize=4）把每次改动算作**两个可审块**（够演接受 + 拒绝），
// 且两相改动落在不相交的行段（第二相不依赖第一相的结果串）。
const NOTES_SEED = 'alpha\nbeta\ngamma\ndelta\nepsilon\nzeta\neta\ntheta\niota\n'
  + 'kappa\nlambda\nmu\nnu\nxi\nomicron\npi\nrho\n';
let iso = null;
let mock = null;

async function startMockLlm() {
  const port = await freePort();
  const child = spawn(process.execPath, [join(REPO, 'tests/fixtures/diff-canvas-native/mock-llm.mjs'), String(port)], {
    stdio: ['pipe', 'pipe', 'pipe'],
  });
  let out = '';
  child.stdout.on('data', (d) => { out += d; });
  child.stderr.on('data', (d) => { out += d; });
  await sleep(400);
  log('mock-llm on', port);
  return { port, child, log: () => out };
}

async function startIsolated() {
  mkdirSync(ISO_BASE, { recursive: true });
  const root = mkdtempSync(join(ISO_BASE, 'run-'));
  const home = join(root, 'home');
  mkdirSync(home, { recursive: true });
  if (resolve(home) === resolve(HOST_ROOT) || resolve(home).startsWith(resolve(HOST_ROOT) + '/')) throw new Error('refusing host data root');

  // 最小 home + mock provider（真引擎；零外部网络）
  // `llm.fallback.preferred` = **全局默认模型的唯一源**（FallbackChain.refs 只读
  // preferred；无本节则 MODEL-CHAIN-EMPTY，派发被拒）。
  writeFileSync(join(home, 'nebflow.json'), JSON.stringify({
    workSchedule: { enabled: false },
    safety: { defaultMode: 'auto-all' },
    // 插件分配腿会先调「小模型槽」（`agents/small-llm`）决定给 delegate 注入哪些
    // 能力；隔离 home 里没有可用小模型 ⇒ 分配 fail-closed ⇒ **Delegate 工具被拒**
    // （首轮真派发失败的根因）。本批不需要插件注入 ⇒ 按配置跳过分配腿
    // （`autoAllocate=false`，引擎自身的合法短路：零模型调用、空集分配成功）。
    plugins: { autoAllocate: false },
    llm: {
      providers: { mock: { baseUrl: `http://127.0.0.1:${mock.port}/v1/`, apiKey: 'sk-mock', protocol: 'openai', models: [{ id: 'mock-1', maxTokens: 8192, contextWindow: 128000 }] } },
      fallback: { preferred: 'mock/mock-1', fallbacks: [] },
    },
  }, null, 2) + '\n');
  writeFileSync(join(home, 'onboarding.json'), JSON.stringify({ state: 'skipped' }) + '\n');
  // agents 面（只读拷贝宿主；免 404 控制台噪音）
  try { cpSync(join(HOST_ROOT, 'agents'), join(home, 'agents'), { recursive: true, filter: (s) => !s.includes('.bak') }); } catch { /* optional */ }
  // ⚠ 宿主的每个 `agents/<name>/agent.json` 都带着**宿主自己的**显式模型链
  // （`model.preferred = <宿主 provider>/…`）——它经 `SchemePolicy.resolveFromInputs`
  // 的 own/main 腿**压过** `llm.fallback` 全局默认；这些 provider 在隔离 home 里
  // 不存在 ⇒ 链解不出 ⇒ MODEL-CHAIN-EMPTY（真会话/真 delegate/真小模型槽全线堵死）。
  // 统一覆盖为隔离内可解析的 mock 链（真引擎 / 真路由 / 真工具链不变，只换模型端点）。
  const agentsDir = join(home, 'agents');
  if (existsSync(agentsDir)) {
    for (const name of readdirSync(agentsDir)) {
      const aj = join(agentsDir, name, 'agent.json');
      if (!existsSync(aj)) continue;
      let obj = {};
      try { obj = JSON.parse(readFileSync(aj, 'utf8')); } catch { obj = {}; }
      obj.model = { preferred: 'mock/mock-1', fallbacks: [] };
      writeFileSync(aj, JSON.stringify(obj) + '\n');
    }
  }

  // 真项目 + 真工作区（**真 git 仓库**：filechanges 面的数据源 = 真 git diff）
  const ws = join(home, 'ws-' + PROJECT);
  mkdirSync(ws, { recursive: true });
  writeFileSync(join(ws, 'project-notes.md'), `# ${PROJECT}\n\nreal-canvas evidence workspace.\n`);
  writeFileSync(join(ws, 'notes.md'), NOTES_SEED);
  writeFileSync(join(ws, 'welcome.md'), '# 欢迎\n\n第一行\n第二行\n第三行\n');
  writeFileSync(join(ws, '.gitignore'), '.nebflow/\n');
  git(ws, ['init', '-q']);
  git(ws, ['add', '-A']);
  git(ws, ['commit', '-q', '-m', 'baseline']);
  // 真改动（未提交）⇒ FileChangeReport 的 git 腿将产出真 diff 文本
  writeFileSync(join(ws, 'welcome.md'), '# 欢迎\n\n第一行（已改）\n第二行\n第三行\n第四行（新）\n');
  writeFileSync(join(ws, 'release-notes.md'), '新增文件\n第二行\n');

  const projDir = join(home, 'projects', PROJECT);
  mkdirSync(projDir, { recursive: true });
  writeFileSync(join(projDir, 'project.json'), JSON.stringify({ name: PROJECT, workspace: ws, agentFile: join(ws, 'project-notes.md'), createdAt: 1763600000000 }, null, 2) + '\n');

  const port = await freePort();
  const base = `http://127.0.0.1:${port}`;
  const env = { ...process.env, STAND_HOME: home, NEBFLOW_HOME: home, NEBFLOW_GATEWAY_PORT: String(port) };
  delete env.STAND_OPEN_BROWSER; delete env.NEBFLOW_OPEN_BROWSER;
  const child = spawn('java', ['-cp', CP, 'nebflow.Main', '--home', home, '--port', String(port), '--no-browser', '--foreground'], {
    cwd: REPO, env, stdio: ['ignore', 'pipe', 'pipe'],
  });
  let eng = '';
  child.stdout.on('data', (d) => { eng += d; });
  child.stderr.on('data', (d) => { eng += d; });
  const ok = await waitHealth(base, 90_000);
  if (!ok) { try { child.kill('SIGKILL'); } catch {} throw new Error('engine not ready:\n' + eng.slice(-2000)); }
  log('isolated ready', base, 'home', home);
  writeFileSync(join(OUT, '_engine.log'), eng);
  child.stdout.on('data', (d) => { writeFileSync(join(OUT, '_engine.log'), eng); });
  child.stderr.on('data', (d) => { writeFileSync(join(OUT, '_engine.log'), eng); });
  return { root, home, ws, port, base, child, engineLog: () => eng };
}

function stopProcess(child) { try { child.kill('SIGTERM'); } catch { /* */ } }

const shots = [];
const report = { generatedAt: new Date().toISOString(), images: [], notes: [] };

/** 真 canvas 凭据：页面 URL + canvas 容器在页面内 + 该容器链的可视性/宽度读数。 */
async function canvasCredential(page, tabIdPrefix) {
  return page.evaluate((prefix) => {
    const panes = Array.from(document.querySelectorAll('.canvas-tab-pane'));
    const mate = panes.filter((e) => String(e.dataset.tabId || '').startsWith(prefix));
    const pane = mate[mate.length - 1] || null;
    const chain = [];
    let el = pane;
    for (let i = 0; el && i < 9; i++, el = el.parentElement) {
      const s = getComputedStyle(el);
      chain.push(`${el.tagName.toLowerCase()}${el.id ? '#' + el.id : ''}.${String(el.className || '').trim().split(/\s+/).slice(0, 2).join('.')}[d=${s.display} v=${s.visibility} w=${Math.round(el.getBoundingClientRect().width)} h=${Math.round(el.getBoundingClientRect().height)}]`);
    }
    const rect = pane ? pane.getBoundingClientRect() : null;
    return {
      pageUrl: location.href,
      canvasContainer: {
        selector: pane ? `.canvas-tab-pane[data-tab-id="${pane.dataset.tabId}"]` : null,
        present: !!pane,
        tabId: pane ? pane.dataset.tabId : null,
        active: pane ? pane.classList.contains('active') : null,
        display: pane ? getComputedStyle(pane).display : null,
        visibility: pane ? getComputedStyle(pane).visibility : null,
        rect: rect ? { x: Math.round(rect.x), y: Math.round(rect.y), w: Math.round(rect.width), h: Math.round(rect.height) } : null,
      },
      allCanvasTabs: panes.map((e) => e.dataset.tabId),
      containerChain: chain,
      bodyMobileLayer: document.body.classList.contains('mobile-layer'),
      // 手机层「当前在前台的是哪一页」——凭据的一部分：canvas 页没在前台时，
      // 这份截图就不是「在真 canvas 里看」的证据。
      mobilePage: (() => {
        const pages = [...document.querySelectorAll('.mobile-page')];
        const on = pages.filter((p) => getComputedStyle(p).display !== 'none' && p.getBoundingClientRect().width > 0);
        return { active: on.map((p) => p.dataset.page), canvasVisible: on.some((p) => p.dataset.page === 'canvas') };
      })(),
      canvasPanelPresent: !!document.querySelector('#canvas-panel'),
      viewport: { w: window.innerWidth, h: window.innerHeight },
    };
  }, tabIdPrefix);
}

async function shot(page, name, cred) {
  const file = join(OUT, name);
  await page.screenshot({ path: file });
  shots.push(file);
  if (cred) report.images.push({ image: name, ...cred });
  log('shot', name);
  return file;
}

async function main() {
  mock = await startMockLlm();
  iso = await startIsolated();
  const token = readFileSync(join(iso.home, 'auth.json'), 'utf8').trim().replace(/^"|"$/g, '');
  const notesAbs = join(iso.ws, 'notes.md');

  // 诊断：引擎实际持有的候选链 + 配置回读（真面读数，非推测）
  try {
    const plan = await (await fetch(iso.base + '/api/models/candidates', { headers: { authorization: `Bearer ${token}` } })).json();
    log('candidate plan', JSON.stringify({ source: plan.source, chain: plan.chain, candidates: plan.candidates, skipped: plan.skipped, warnings: plan.warnings }));
    report.notes.push({ candidatePlan: { source: plan.source, chain: plan.chain, candidates: plan.candidates } });
  } catch (e) { log('candidate plan err', String(e).slice(0, 160)); }

  const browser = await chromium.launch();
  try {
    const ctx = await browser.newContext({ viewport: { width: 375, height: 812 }, deviceScaleFactor: 3, isMobile: true, hasTouch: true, colorScheme: 'light' });
    await ctx.addInitScript(() => {
      localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
      const Orig = window.WebSocket;
      window.__nfFrames = { in: [], out: [] };
      function W(...a) { const ws = new Orig(...a); ws.addEventListener('message', (e) => { try { window.__nfFrames.in.push(JSON.parse(e.data)); } catch {} }); const s = ws.send.bind(ws); ws.send = (d) => { try { window.__nfFrames.out.push(JSON.parse(d)); } catch {} s(d); }; return ws; }
      W.prototype = Orig.prototype; W.CONNECTING = 0; W.OPEN = 1; W.CLOSING = 2; W.CLOSED = 3; window.WebSocket = W;
    });
    const page = await ctx.newPage();
    const errors = [];
    page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 200)));
    page.on('console', (m) => { if (m.type() === 'error') errors.push('console: ' + m.text().slice(0, 200)); });

    const t = await (await fetch(iso.base + '/api/auth/console-ticket', { method: 'POST', headers: { authorization: `Bearer ${token}` } })).json();
    await page.goto(`${iso.base}/?ticket=${encodeURIComponent(t.ticket)}`, { waitUntil: 'domcontentloaded' });
    await page.waitForSelector('#activity-bar', { state: 'attached', timeout: 30000 });
    await page.waitForFunction(() => document.readyState === 'complete');
    await sleep(1500);
    log('page URL', page.url());

    // 动效帧采集器（真交互全程：产物卡点击 → canvas 滑入 → 切页签 → 逐块/全量接受）
    let fn = 0;
    const grab = async () => { await page.screenshot({ path: join(FRAMES, `f${String(++fn).padStart(3, '0')}.png`) }); };

    // ── 面 A：filechanges —— 真 delegate 批次终态 ─────────────────────────
    // 真用户路径：在会话输入条里让 Nebula 真派发一次 Delegate（真工具 + 真会话）
    const chatTa = page.locator('#input-area textarea').first();
    await chatTa.click().catch(() => {});
    await grab(); // 帧 1：真会话面（真 delegate 指令入站前）
    await chatTa.fill('delegate now: 在 real-canvas-demo 里跑一个真实批次').catch((e) => log('chat fill err', String(e).slice(0, 120)));
    await chatTa.press('Enter').catch((e) => log('chat enter err', String(e).slice(0, 120)));
    log('delegate prompt sent');
    let fcOk = false;
    try {
      await page.waitForSelector('[data-fc-batch]', { state: 'attached', timeout: 120_000 });
      fcOk = true;
    } catch (e) { log('fileChanges chip wait err', String(e).slice(0, 160)); }
    log('fileChanges chip present', fcOk);
    if (fcOk) {
      const fcJson = await page.evaluate(() => {
        const el = document.querySelector('[data-fc-batch]');
        const inFrames = (window.__nfFrames.in || []).filter((f) => f && f.fileChanges);
        return { chipText: (el.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 120), frameFileChanges: inFrames.length ? inFrames[inFrames.length - 1].fileChanges : null };
      });
      log('fileChanges payload (from WS frame)', JSON.stringify({ chipText: fcJson.chipText, nodeId: fcJson.frameFileChanges?.nodeId, root: fcJson.frameFileChanges?.root, files: (fcJson.frameFileChanges?.files || []).map((f) => ({ path: f.path, changeType: f.changeType, added: f.added, removed: f.removed, source: f.source, diffLines: String(f.diffText || '').split('\n').length })) }));
      report.notes.push({ fileChangesWire: { chipText: fcJson.chipText, nodeId: fcJson.frameFileChanges?.nodeId, root: fcJson.frameFileChanges?.root, files: (fcJson.frameFileChanges?.files || []).map((f) => ({ path: f.path, changeType: f.changeType, added: f.added, removed: f.removed, source: f.source })) } });
      await grab();
      await page.locator('[data-fc-batch]').first().click({ timeout: 5000 }).catch((e) => log('chip click err', String(e).slice(0, 120)));
      await sleep(180); await grab(); await sleep(180); await grab();
      await page.waitForSelector('.canvas-tab-pane[data-tab-id^="filechanges:"]', { state: 'visible', timeout: 20000 }).catch((e) => log('filechanges canvas wait err', String(e).slice(0, 160)));
      // 手机层：把「看板」页提到栈顶（app 自身的开卡意图事件）
      await page.evaluate(() => document.dispatchEvent(new CustomEvent('nf:canvas-open-intent')));
      await sleep(400); await grab();
      await sleep(900);
      let cred = await canvasCredential(page, 'filechanges:');
      cred.dataOrigin = {
        kind: 'engine-delegate-terminal',
        detail: 'real Delegate tool call by the isolated engine → terminal ExternalEvent `fileChanges` metadata → FileChangeReport git leg (`git status --porcelain` + `git diff HEAD`) over the real git repo at `root`; frontend opened it through the real chip click (`[data-fc-batch]` → diffPanel.toggleDiffBatch)',
        gitStatus: git(iso.ws, ['status', '--porcelain']),
      };
      await shot(page, 'filechanges-mobile-light-375x812.png', cred);
      await page.emulateMedia({ colorScheme: 'dark' }); await sleep(700);
      await shot(page, 'filechanges-mobile-dark-375x812.png', await canvasCredential(page, 'filechanges:'));
      await page.emulateMedia({ colorScheme: 'light' }); await sleep(600);
      fcOk = cred.canvasContainer.present === true;
      log('filechanges canvas pane present', fcOk, JSON.stringify(cred.canvasContainer));
    }

    // ── 面 B：askreview —— 真 ask_edit 回合 ──────────────────────────────
    const sid = 'ask-' + createHash('sha256').update(notesAbs).digest('hex').slice(0, 8);
    const reviewSel = `.canvas-tab-pane[data-tab-id="askreview:${sid}"]`;
    log('ask sid', sid);

    /** 手机层 canvas 页必须真在前台（resize 回来时栈会回落到 chat）。 */
    const showCanvasPage = async () => {
      await page.waitForSelector('.mobile-page[data-page="canvas"]', { state: 'attached', timeout: 8000 }).catch(() => {});
      await page.evaluate(() => document.dispatchEvent(new CustomEvent('nf:canvas-open-intent')));
      await sleep(500);
      await page.waitForSelector('.mobile-page[data-page="canvas"]', { state: 'visible', timeout: 8000 })
        .catch((e) => log('mobile canvas page wait err', String(e).slice(0, 120)));
      await sleep(400);
    };

    await page.evaluate((args) => {
      window.dispatchEvent(new CustomEvent('workspace-open-item', {
        detail: { id: 'file:' + args.abs, itemType: 'markdown', title: 'notes.md', content: args.seed, absPath: args.abs, pinned: true },
      }));
    }, { abs: notesAbs, seed: NOTES_SEED });
    await sleep(500);
    await page.evaluate(async (abs) => {
      const m = await import('/js/canvas.js');
      m.openCanvas('notes.md');
      m.setActiveTab('file:' + abs);
    }, notesAbs).catch((e) => log('openCanvas err', String(e).slice(0, 200)));
    await showCanvasPage();
    await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:${notesAbs}"]`, { state: 'visible', timeout: 8000 }).catch((e) => log('file tab wait err', String(e).slice(0, 120)));
    await sleep(500);

    /** 走真用户路径开一个 ask 回合并等审阅页签（同一文件 ⇒ 同一 sid/页签）。 */
    const openAskRound = async (prompt) => {
      await showCanvasPage();
      await page.evaluate(async (abs) => {
        const m = await import('/js/canvas.js');
        m.setActiveTab('file:' + abs);
      }, notesAbs).catch(() => {});
      await sleep(600);
      await page.locator('.ask-fab').first().click({ timeout: 6000 }).catch((e) => log('ask-fab click err', String(e).slice(0, 120)));
      await sleep(1200);
      const ta = page.locator('.ask-input textarea').first();
      await ta.click().catch(() => {});
      await ta.fill(prompt).catch((e) => log('fill err', String(e).slice(0, 120)));
      await sleep(250);
      await ta.press('Enter').catch((e) => log('enter err', String(e).slice(0, 120)));
      await sleep(400);
      await page.locator('.ask-send').first().click({ timeout: 3000, force: true }).catch(() => {});
      await page.waitForSelector(reviewSel, { state: 'visible', timeout: 60000 }).catch((e) => log('askreview wait err', String(e).slice(0, 160)));
      await sleep(1400);
      return page.evaluate((sel) => {
        const p = document.querySelector(sel);
        return p ? { hunksHint: p.querySelectorAll('.nf-dv-chunkbtn').length } : null;
      }, reviewSel).catch(() => null);
    };

    await openAskRound('do ask edit now');
    const revCred = await canvasCredential(page, `askreview:${sid}`);
    revCred.dataOrigin = {
      kind: 'engine-ask-edit-round',
      detail: 'real ask session → mock LLM emitted a real `ask_edit` tool call (multi-hunk) → engine wrote the bound file and pushed `askEditApplied`; the inline review tab is the engine-pushed aggregate review state',
      appliedDiff: readFileSync(notesAbs, 'utf8'),
      sid,
    };
    await shot(page, 'askreview-mobile-light-375x812.png', revCred);

    // ── 手机层 canvas 底部控件带 **全族枪测**（真实例 · 同一页）────────────
    // 族清单与进带判据见 css/mobileLayer.css「底部控件带的族清单与进带判据」块；
    // 判据 = 每颗交互件取**中心点**跑 elementFromPoint 命中自身 + 与 #mobile-dock
    // 矩形零交叠。同一运行里既测「修复在场」，也测「撤掉本批修复」（负控）。
    const BAND_SWEEP = `(() => {
      const R = (el) => { const r = el.getBoundingClientRect(); return { x:+r.x.toFixed(1), y:+r.y.toFixed(1), w:+r.width.toFixed(1), h:+r.height.toFixed(1),
        right:+r.right.toFixed(1), bottom:+r.bottom.toFixed(1), cx:+(r.x+r.width/2).toFixed(1), cy:+(r.y+r.height/2).toFixed(1) }; };
      const vis = (el) => { if (!el) return false; const s = getComputedStyle(el);
        return el.getClientRects().length > 0 && s.display !== 'none' && s.visibility === 'visible' && Number(s.opacity) > 0; };
      const dock = document.getElementById('mobile-dock');
      const dockBox = dock ? R(dock) : null;
      const overlap = (a, b) => (a && b) ? Math.max(0, Math.min(a.right,b.right)-Math.max(a.x,b.x)) * Math.max(0, Math.min(a.bottom,b.bottom)-Math.max(a.y,b.y)) : 0;
      const round = (n) => Math.round(n * 100) / 100;
      const fams = [
        { fam:'left-pair',  sel:'.canvas-find-toggle' },
        { fam:'left-pair',  sel:'.canvas-ref-select' },
        { fam:'dots',       sel:'.mf-dots' },
        { fam:'ask-fab',    sel:'.ask-fab' },
        { fam:'review-band',sel:'.nf-dv-allbar .nf-dv-allbtn' },
      ];
      const families = [];
      for (const f of fams) {
        for (const el of document.querySelectorAll(f.sel)) {
          if (f.sel === '.ask-fab' && el.classList.contains('note-fab')) continue;
          // 实况里某族可能**合法缺席**（审阅面上的左下工具对 / 气泡钮吃 gate-off）——
          // 缺席记 n/a（不进 ok 汇总），在场才过判据。
          if (!vis(el)) { families.push({ fam:f.fam, sel:f.sel, visible:false, applicable:false, why:'not-visible' }); continue; }
          const box = R(el);
          const t = document.elementFromPoint(box.cx, box.cy);
          const hitSelf = !!(t && (t === el || el.contains(t) || t.contains(el)));
          const ov = round(overlap(box, dockBox));
          families.push({ fam:f.fam, sel:f.sel, visible:true, applicable:true, box, hitSelf,
            hitTarget: t ? (t.tagName || '').toLowerCase() + (t.className && typeof t.className === 'string' ? '.' + t.className.trim().split(/\\s+/).slice(0,2).join('.') : '') : null,
            dockOverlapPx: ov, ok: hitSelf && ov <= 0.5 });
        }
      }
      // 审阅带内每颗钮逐颗过（「每个交互钮」口径）。
      const bandButtons = [...document.querySelectorAll('.nf-dv-allbar .nf-dv-allbtn')].map((b) => {
        const box = R(b); const t = document.elementFromPoint(box.cx, box.cy);
        return { label:(b.textContent||'').trim(), box, disabled: !!b.disabled, hitSelf: !!(t && (t === b || b.contains(t))),
          hitTarget: t ? (t.tagName || '').toLowerCase() : null };
      });
      const bar = document.querySelector('.nf-dv-allbar');
      const applicable = families.filter((f) => f.applicable !== false);
      return { viewport:{ w:innerWidth, h:innerHeight }, dock:dockBox, bandTop: bar ? R(bar).y : null,
        barBox: bar ? R(bar) : null, families, bandButtons,
        familyApplicable: applicable.length,
        familyAllOk: applicable.length > 0 && applicable.every((f) => f.ok === true),
        bandButtonsAllOk: bandButtons.length > 0 && bandButtons.every((b) => b.hitSelf || b.disabled) };
    })()`;

    const sweepFixed = await page.evaluate(BAND_SWEEP);
    log('band sweep (fix in place)', JSON.stringify({ familyAllOk: sweepFixed.familyAllOk, bandButtonsAllOk: sweepFixed.bandButtonsAllOk, bar: sweepFixed.barBox, dock: sweepFixed.dock }));
    log('band sweep families', JSON.stringify(sweepFixed.families.map((f) => ({ fam:f.fam, sel:f.sel, cy:f.box?.cy, ok:f.ok, ov:f.dockOverlapPx, hit:f.hitTarget }))));
    log('band sweep buttons', JSON.stringify(sweepFixed.bandButtons.map((b) => ({ label:b.label, cy:b.box.cy, hitSelf:b.hitSelf, hit:b.hitTarget }))));

    // 负控：撤掉本批修复（只撤销那一条 margin-bottom）⇒ 同族枪测必红。
    const NEGCTL = 'body.mobile-layer #mobile-pages .mf-cv-host > .canvas-tab-pane .nf-dv-allbar{margin-bottom:0 !important}';
    await page.evaluate((css) => {
      const s = document.createElement('style');
      s.id = '__negctl';
      s.textContent = css;
      document.head.appendChild(s);
    }, NEGCTL);
    await sleep(400);
    const sweepFixedRemoved = await page.evaluate(BAND_SWEEP);
    log('band sweep (fix removed = negative control)', JSON.stringify({ familyAllOk: sweepFixedRemoved.familyAllOk, bandButtonsAllOk: sweepFixedRemoved.bandButtonsAllOk, bar: sweepFixedRemoved.barBox }));
    await shot(page, 'askreview-mobile-light-negative-control-375x812.png', await canvasCredential(page, `askreview:${sid}`));
    await page.evaluate(() => { const s = document.getElementById('__negctl'); if (s) s.remove(); });
    await sleep(400);
    const sweepRestored = await page.evaluate(BAND_SWEEP);
    log('band sweep (restored)', JSON.stringify({ familyAllOk: sweepRestored.familyAllOk }));
    report.notes.push({
      bandSweep: {
        criterion: 'mobile canvas bottom band: per interactive control, elementFromPoint(centre) must hit itself AND its rect must not overlap #mobile-dock',
        familiesInBand: ['left-pair .canvas-find-toggle/.canvas-ref-select', 'dots .mf-dots', 'ask-fab .ask-fab', 'review-band .nf-dv-allbar .nf-dv-allbtn (each button; the .nf-dv-allbar container is a transparent flow row, not an interactive control)'],
        notInBand: ['source/zoom retired on mobile (display:none)', 'chunk-level .nf-dv-chunkbtn = in-flow content control (see its own dock-overlap readout)'],
        gate: 'enforced by tests/canvas-band-hittability.spec.mjs (B0-B10; the same family table + the negative control)',
        fixed: sweepFixed, fixRemoved: sweepFixedRemoved, restored: sweepRestored,
      },
    });

    // 暗档审阅面（真点按之前先留一张未动的暗档）
    await page.emulateMedia({ colorScheme: 'dark' }); await sleep(700);
    await shot(page, 'askreview-mobile-dark-375x812.png', await canvasCredential(page, `askreview:${sid}`));
    await page.emulateMedia({ colorScheme: 'light' }); await sleep(600);

    const switchTo = async (prefix) => {
      await page.evaluate(async (p) => {
        const m = await import('/js/canvas.js');
        const pane = Array.from(document.querySelectorAll('.canvas-tab-pane')).find((e) => String(e.dataset.tabId || '').startsWith(p));
        if (pane) m.setActiveTab(pane.dataset.tabId);
      }, prefix);
    };
    const tabList = () => page.evaluate(() => [...document.querySelectorAll('.canvas-tab-pane')].map((e) => e.dataset.tabId));
    const chunkState = () => page.evaluate((sel) => {
      const p = document.querySelector(sel);
      if (!p) return { reviewPane: false };
      return { reviewPane: true, states: [...p.querySelectorAll('[data-chunk-state]')].map((c) => c.getAttribute('data-chunk-state')),
        pendingChunkButtons: p.querySelectorAll('.nf-dv-chunkbtn').length };
    }, reviewSel).catch(() => null);

    // ── 桌面 1440（**真点按之前**取：全部接受后审阅页签会收口）──────────────
    await page.setViewportSize({ width: 1440, height: 900 });
    await sleep(1500);
    if (fcOk) {
      await switchTo('filechanges:'); await sleep(900);
      await shot(page, 'filechanges-desktop-1440x900.png', await canvasCredential(page, 'filechanges:'));
    } else {
      log('desktop filechanges shot skipped (no filechanges tab)');
    }
    await switchTo(`askreview:${sid}`); await sleep(900);
    await shot(page, 'askreview-desktop-1440x900.png', await canvasCredential(page, `askreview:${sid}`));

    await setViewportSafe(page, 375, 812); await sleep(1200);
    await showCanvasPage();
    await switchTo('filechanges:'); await sleep(250); await grab(); await sleep(250); await grab();
    await switchTo(`askreview:${sid}`); await sleep(400); await grab(); await sleep(300); await grab();
    const beforeClicks = await chunkState();
    log('review state before real clicks', JSON.stringify(beforeClicks));

    const clickChunk = async (kind) => {
      const sel = `${reviewSel} .nf-dv-chunkbtn-${kind}`;
      await page.waitForSelector(sel, { state: 'visible', timeout: 8000 }).catch((e) => log(`chunk-${kind} visible wait err`, String(e).slice(0, 120)));
      await page.locator(sel).first().click({ timeout: 6000 }).catch((e) => log(`chunk-${kind} click err`, String(e).slice(0, 140)));
    };

    // ── 真点按 A：逐块「拒绝」（块 1）→ 行内状态可见变化（data-chunk-state=rejected）
    //    先拒后接：拒绝后审阅里**还剩一块**，于是这一帧能看见「块数 2→1」的可见变化
    //    （若最后一块才点，引擎立刻收口，页签当场消失，拍不到余量）。──
    await clickChunk('reject');
    const afterReject = await chunkState();     // 真点刚落地就取（mousedown 同步落态）
    await sleep(300); await grab();
    log('after real chunk-reject', JSON.stringify(afterReject));
    await shot(page, 'askreview-mobile-light-chunk-rejected-375x812.png', { ...(await canvasCredential(page, `askreview:${sid}`)), clickEffect: { action: '逐块拒绝（真点 · mousedown 就地落态）', before: beforeClicks, ...afterReject } });
    await page.emulateMedia({ colorScheme: 'dark' }); await sleep(600);
    await shot(page, 'askreview-mobile-dark-chunk-rejected-375x812.png', await canvasCredential(page, `askreview:${sid}`));
    await page.emulateMedia({ colorScheme: 'light' }); await sleep(700); await grab();
    const afterRejectSettled = await chunkState();

    // ── 真点按 B：逐块「接受」（余下的块）→ 行内状态可见变化（data-chunk-state=accepted）──
    await clickChunk('accept');
    const afterAccept = await chunkState();
    await sleep(300); await grab();
    log('after real chunk-accept', JSON.stringify(afterAccept));
    await shot(page, 'askreview-mobile-light-chunk-accepted-375x812.png', { ...(await canvasCredential(page, `askreview:${sid}`)), clickEffect: { action: '逐块接受（真点 · mousedown 就地落态）', before: afterRejectSettled, ...afterAccept } });
    await page.emulateMedia({ colorScheme: 'dark' }); await sleep(600);
    await shot(page, 'askreview-mobile-dark-chunk-accepted-375x812.png', await canvasCredential(page, `askreview:${sid}`));
    await page.emulateMedia({ colorScheme: 'light' }); await sleep(900); await grab();
    // 第 1 回合两块都由逐块动作了结 ⇒ 引擎收口（真帧 askEditResolved）⇒ 审阅页签收起。
    await sleep(900); await grab();
    const tabsAfterRound1 = await tabList();
    const fileAfterRound1 = readFileSync(notesAbs, 'utf8');
    log('round1 settled', JSON.stringify({ tabsAfterRound1, fileAfterRound1 }));

    // ── 第 2 回合：底部控件带「全部接受」真点 → 审阅收口 + 盘上定稿 ──────────
    //    这颗钮正是本次被 `#mobile-dock` 压住的那一颗：修复前它点不到，所以这一击
    //    本身就是「修复生效」的行为证据（不只是几何）。
    const round2 = await openAskRound('do ask edit now phase2');
    const reviewOpenBefore = (await tabList()).some((t) => String(t).startsWith(`askreview:${sid}`));
    await showCanvasPage();
    await switchTo(`askreview:${sid}`); await sleep(400); await grab(); await sleep(300); await grab();
    await page.waitForSelector(`${reviewSel} .nf-dv-allbtn.nf-dv-accept-all`, { state: 'visible', timeout: 10000 }).catch((e) => log('accept-all visible wait err', String(e).slice(0, 140)));
    await page.locator(`${reviewSel} .nf-dv-allbtn.nf-dv-accept-all`).first().click({ timeout: 6000 }).catch((e) => log('accept-all click err', String(e).slice(0, 140)));
    await sleep(1600); await grab(); await sleep(1200); await grab();
    const resolvedTabs = await tabList();
    const fileAfterAcceptAll = readFileSync(notesAbs, 'utf8');
    log('tabs after real accept-all', JSON.stringify(resolvedTabs), 'file=', JSON.stringify(fileAfterAcceptAll));
    await shot(page, 'askreview-mobile-light-acceptall-resolved-375x812.png', { ...(await canvasCredential(page, 'file:')), clickEffect: { action: '全部接受（真点 · 底部控件带那颗钮）', round2Hunks: round2, reviewOpenBefore, reviewClosedAfter: !resolvedTabs.some((t) => String(t).startsWith(`askreview:${sid}`)), tabsAfter: resolvedTabs, fileOnDisk: fileAfterAcceptAll } });
    report.notes.push({ realClicks: { before: beforeClicks, chunkRejected: afterReject, chunkRejectedSettled: afterRejectSettled, chunkAccepted: afterAccept, round1: { tabsAfterRound1, fileAfterRound1 }, round2: { hunks: round2, reviewOpenBefore, resolvedTabs, fileOnDisk: fileAfterAcceptAll } } });

    console.log('DIAG ' + JSON.stringify({ errors: errors.slice(0, 8), fileChangesChip: fcOk }));
    const frames = await page.evaluate(() => ({ in: (window.__nfFrames.in || []).map((f) => f.type || Object.keys(f).slice(0, 3)).slice(-25) }));
    console.log('FRAMES ' + JSON.stringify(frames));
    console.log('MOCKLOG\n' + mock.log().slice(-2500));
    await ctx.close();
  } finally {
    await browser.close();
  }

  // ── 夹具页（仅作对照，不作证据）──────────────────────────────────────
  // 夹具页 `compare.html` 走绝对 `/css/...`、`/js/...` 路径 ⇒ 必须由一个以
  // **真前端源码树** (`src/main/resources/web`) 为根的静态服务器承载（`file://` 下
  // 那些路径会落到文件系统根，页面必崩）。仅用于「夹具 vs 真 canvas」并排对照。
  let fixtureUrl = null;
  let srv = null;
  let fBrowser = null;
  try {
    const webDir = join(REPO, 'src/main/resources/web');
    const harnessSrc = join(REPO, 'tests/fixtures/diff-canvas-native/compare.html');
    const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png' };
    const port2 = await freePort();
    const srvLocal = createHttpServer((req, res) => {
      const p = decodeURIComponent(new URL(req.url, `http://127.0.0.1:${port2}`).pathname);
      if (p === '/__harness.html') { res.writeHead(200, { 'Content-Type': MIME['.html'] }); res.end(readFileSync(harnessSrc)); return; }
      const f = join(webDir, p);
      if (!f.startsWith(webDir) || !existsSync(f)) { res.writeHead(404); res.end('nf'); return; }
      res.writeHead(200, { 'Content-Type': MIME[extname(f)] || 'application/octet-stream' }); res.end(readFileSync(f));
    });
    await new Promise((r) => srvLocal.listen(port2, '127.0.0.1', r));
    srv = srvLocal;
    const b2 = await chromium.launch();
    fBrowser = b2;
    const p2 = await b2.newPage({ viewport: { width: 375, height: 812 }, deviceScaleFactor: 3, isMobile: true, hasTouch: true });
    await p2.addInitScript(() => { localStorage.setItem('nebflow_locale', 'zh-CN'); });
    await p2.goto(`http://127.0.0.1:${port2}/__harness.html`);
    await p2.waitForFunction('window.__ready === true', null, { timeout: 25000 });
    await p2.evaluate(() => { window.__hideCmp(true); window.__showPage('canvas'); });
    await sleep(900);
    fixtureUrl = p2.url();
    await p2.screenshot({ path: join(OUT, '_fixture-compare.png') });
    await b2.close();
    fBrowser = null;
    report.notes.push({ fixturePage: fixtureUrl, fixturePageRole: 'comparison page — NOT evidence, reference only' });
    // 并排图：真 canvas（左）vs 夹具页（右）
    const b3 = await chromium.launch();
    fBrowser = b3;
    const p3 = await b3.newPage({ viewport: { width: 1560, height: 1600 }, deviceScaleFactor: 2 });
    const realB64 = readFileSync(join(OUT, 'askreview-mobile-light-375x812.png')).toString('base64');
    const fixB64 = readFileSync(join(OUT, '_fixture-compare.png')).toString('base64');
    await p3.setContent(`<body style="margin:0;background:#202124;font:600 22px system-ui;color:#eee;display:flex;gap:24px;padding:20px">
      <div style="flex:1"><div style="padding:8px 0">真 canvas（真隔离实例 · 375×812）</div><img style="width:100%;border:1px solid #555" src="data:image/png;base64,${realB64}"></div>
      <div style="flex:1"><div style="padding:8px 0">夹具/对照页（compare.html —— 仅对照，不作证据）</div><img style="width:100%;border:1px solid #555" src="data:image/png;base64,${fixB64}"></div>
    </body>`, { waitUntil: 'load' });
    await sleep(600);
    await p3.screenshot({ path: join(OUT, 'canvas-vs-fixture-side-by-side.png'), fullPage: true });
    await b3.close();
    fBrowser = null;
    shots.push(join(OUT, 'canvas-vs-fixture-side-by-side.png'));
  } catch (e) { log('fixture compose err', String(e).slice(0, 200)); }
  finally {
    // 夹具腿中途抛错时不许漏掉浏览器 / 静态服务器句柄（否则 node 事件循环不退出）。
    if (fBrowser) { try { await fBrowser.close(); } catch { /* */ } }
    if (srv) { await new Promise((r) => srv.close(r)); }
  }

  // ── 动效 GIF（帧 → ffmpeg；>10MB 转 MP4）─────────────────────────────
  let gifInfo = null;
  try {
    const frameFiles = readdirSync(FRAMES).filter((f) => f.endsWith('.png')).sort();
    if (frameFiles.length >= 3) {
      const gif = join(OUT, 'motion-real-canvas.gif');
      const r = spawnSync('ffmpeg', ['-y', '-loglevel', 'error', '-framerate', '8', '-i', join(FRAMES, 'f%03d.png'),
        '-vf', 'fps=8,scale=iw*0.6:-1:flags=lanczos,split[s0][s1];[s0]palettegen[p];[s1][p]paletteuse', '-loop', '0', gif], { encoding: 'utf8' });
      if (r.status !== 0) throw new Error(r.stderr || 'ffmpeg failed');
      const mb = statSync(gif).size / (1024 * 1024);
      const pr = spawnSync('ffprobe', ['-v', 'error', '-select_streams', 'v:0', '-show_entries', 'stream=width,height,nb_frames,avg_frame_rate,duration', '-of', 'json', gif], { encoding: 'utf8' });
      gifInfo = { file: 'motion-real-canvas.gif', bytes: statSync(gif).size, mb: Number(mb.toFixed(2)), probe: JSON.parse(pr.stdout || '{}') };
      if (mb > 10) {
        const mp4 = join(OUT, 'motion-real-canvas.mp4');
        spawnSync('ffmpeg', ['-y', '-loglevel', 'error', '-framerate', '6', '-i', join(FRAMES, 'f%03d.png'), '-pix_fmt', 'yuv420p', mp4], { encoding: 'utf8' });
        gifInfo.mp4 = { file: 'motion-real-canvas.mp4', bytes: statSync(mp4).size };
      }
      log('gif', JSON.stringify(gifInfo));
    }
  } catch (e) { log('gif err', String(e).slice(0, 200)); }

  report.motion = gifInfo;
  report.shots = shots;
  report.engineLogTail = iso.engineLog().split('\n').slice(-40);
  writeFileSync(join(OUT, 'real-canvas-manifest.json'), JSON.stringify(report, null, 2));
  console.log('shots:', shots.map((s) => s.split('/').pop()));
}

/** setViewportSize is unavailable on a closed page; tolerate. */
async function setViewportSafe(page, w, h) { try { await page.setViewportSize({ width: w, height: h }); } catch { /* */ } }

try {
  await main();
} catch (e) {
  console.error('FAILED:', e && e.stack ? e.stack : String(e));
  if (iso) console.error('engine tail:\n' + iso.engineLog().slice(-3000));
  process.exitCode = 1;
} finally {
  if (mock) stopProcess(mock.child);
  if (iso) { stopProcess(iso.child); await sleep(1500); try { rmSync(iso.root, { recursive: true, force: true }); } catch { /* */ } }
}
