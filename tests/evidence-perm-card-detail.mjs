#!/usr/bin/env node
// evidence-perm-card-detail.mjs — 审批卡三要素的**真机取证**（报障批 2026-11-19）。
//
// 作者报障：控制台 delegate 审批卡「这个危险操作给的信息太少了」。本脚本按真实
// 通路跑一遍，并留下真图：
//   1. 隔离实例（--home <tmp> --port 8291，**绝不碰 :6138/PID 56786**）+ 真 delegate；
//   2. 真 delegate = 由 Nebula 会话经 Delegate 工具派生的 delegate-<agent>-<8hex> 会话
//      （交付面 mock LLM 只替模型发言，工具执行/权限判定/帧广播全部是引擎真代码）；
//   3. delegate 发起一条**真被判定为危险**的 Bash（`rm -rf` 多行形态）⇒ 真 askPermission 帧；
//   4. 浏览器（真前端）打开控制台 → 卡片三要素截图：1440 亮/暗 × 375 亮/暗 + 展开 GIF；
//   5. 点「拒绝 · 跳过此工具调用」= 既有语义不变（危险命令**没有执行**，探针目录原地不动）；
//   6. 捕获的真帧写成 `tests/fixtures/perm-card-detail/frames.json`（硬闸的真数据来源）。
//
// 用法（worktree 根，前台/后台均可）：
//   node tests/evidence-perm-card-detail.mjs
// Env：GATEWAY_PORT(8291) MOCK_PORT(28991) OUT(.nebflow/evidence/20261119_perm-card-detail)
//      NEBFLOW_HOME(临时) KEEP_HOME=1(保留隔离 home 供排查)

import { spawn, execFileSync } from 'node:child_process';
import http from 'node:http';
import { readFileSync, writeFileSync, appendFileSync, mkdirSync, existsSync, rmSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { tmpdir } from 'node:os';
import { setTimeout as sleep } from 'node:timers/promises';
import WebSocket from 'ws';

const REPO = dirname(dirname(fileURLToPath(import.meta.url)));
const GATEWAY_PORT = Number(process.env.GATEWAY_PORT || 8291);
const MOCK_PORT = Number(process.env.MOCK_PORT || 28991);
const BASE = `http://127.0.0.1:${GATEWAY_PORT}`;
const HOME = process.env.NEBFLOW_HOME || join(tmpdir(), `nb-permcard-${Date.now()}`);
const OUT = process.env.OUT || join(REPO, '.nebflow', 'evidence', '20261119_perm-card-detail');
const SHOTS = join(OUT, 'shots');
const FIXTURE_OUT = join(REPO, 'tests', 'fixtures', 'perm-card-detail', 'frames.json');
const CONFIG_PATH = join(HOME, 'nebflow.json');
// 探针目录：只被「拒绝」的卡指向它 —— 全程不会有任何删除发生（断言里也验这一点）
const PROBE_DIR = join(HOME, 'danger-probe');
const PROBE_A = join(PROBE_DIR, 'keep-a');
const PROBE_B = join(PROBE_DIR, 'keep-b');
const PROBE_C = join(PROBE_DIR, 'keep-c');
const WRITE_PROBE = join(PROBE_DIR, 'delegate-note.txt');

const DANGEROUS_CMD = [
  'set -e',
  `cd ${PROBE_DIR}`,
  '# 清理三份历史快照（本行以下都是真删除，取证时会被拒绝）',
  `rm -rf ${PROBE_A}`,
  `rm -rf ${PROBE_B}`,
  `rm -rf ${PROBE_C}`,
  'rm -rf ./cache-*',
  'rm -rf ./tmp-*',
  'ls -la',
].join('\n');

mkdirSync(OUT, { recursive: true });
mkdirSync(SHOTS, { recursive: true });
mkdirSync(PROBE_DIR, { recursive: true });
for (const p of [PROBE_A, PROBE_B, PROBE_C]) mkdirSync(p, { recursive: true });
const LOG = join(OUT, 'run.log');
writeFileSync(LOG, '');
const log = (s) => {
  const line = `${new Date().toISOString()} ${s}`;
  console.log(line);
  try { appendFileSync(LOG, line + '\n'); } catch { /* best effort */ }
};

let failed = 0;
const checks = [];
function check(name, okRun, extra = '') {
  if (!okRun) failed++;
  checks.push({ name, ok: okRun, extra });
  log(`${okRun ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
}
function guardIsolation() {
  if (GATEWAY_PORT === 6138 || GATEWAY_PORT === 8080) throw new Error('refusing to use production/host ports');
  if (!HOME.startsWith(tmpdir()) && !process.env.NEBFLOW_HOME) throw new Error('isolated home must live under tmp');
  log(`[guard] isolated home=${HOME} port=${GATEWAY_PORT} (production :6138 untouched)`);
}

// ══ stub LLM：两段式（Nebula → Delegate 工具调用；delegate → Bash → Write）══
let stubRounds = 0;
const stubTrace = [];
const requestMeta = [];
function chunk(delta, finish) {
  return 'data: ' + JSON.stringify({
    id: 'chatcmpl-stub', object: 'chat.completion.chunk', created: Math.floor(Date.now() / 1000), model: 'mock-1',
    choices: [{ index: 0, delta, finish_reason: finish ?? null }],
  }) + '\n\n';
}
function stop() {
  return [chunk({ role: 'assistant', content: '' }), chunk({ content: 'done' }), chunk({}, 'stop'), 'data: [DONE]\n\n'].join('');
}
function toolCall(name, args) {
  return [
    chunk({ role: 'assistant', content: '' }),
    chunk({ tool_calls: [{ index: 0, id: `call_${name}_${stubRounds}`, type: 'function', function: { name, arguments: JSON.stringify(args) } }] }),
    chunk({}, 'tool_calls'),
    'data: [DONE]\n\n',
  ].join('');
}
const msgText = (m) => (typeof m?.content === 'string' ? m.content
  : Array.isArray(m?.content) ? m.content.map((p) => (p && typeof p.text === 'string' ? p.text : '')).join('\n') : '');
const allText = (msgs) => msgs.map(msgText).join('\n');

function startMock() {
  const server = http.createServer((req, res) => {
    if (req.method !== 'POST') { res.writeHead(404).end(); return; }
    const parts = [];
    req.on('data', (d) => parts.push(d));
    req.on('end', () => {
      let body = {};
      try { body = JSON.parse(Buffer.concat(parts).toString('utf8')); } catch { /* tolerate */ }
      stubRounds++;
      const msgs = Array.isArray(body.messages) ? body.messages : [];
      const text = allText(msgs);
      const toolMsgs = msgs.filter((m) => m.role === 'tool').length;
      const isDelegate = text.includes('PERM-EVIDENCE-BRIEF');
      requestMeta.push({
        round: stubRounds,
        stream: body.stream,
        model: body.model,
        tools: Array.isArray(body.tools) ? body.tools.map((t) => t?.function?.name).slice(0, 40) : null,
        msgs: msgs.length,
        lastRole: msgs.length ? msgs[msgs.length - 1]?.role : null,
        delegateBrief: isDelegate,
      });
      // 非流式请求（plugin/model allocator 一类辅助调用）：回一条普通 JSON 补全。
      // 判据 = 请求没显式要流（stream !== true）。plugin allocator 的应答契约是
      // `{"plugins": [...], "reason": "..."}`（LlmAllocator.parsePlugins），空数组
      // = 不额外装插件（取证只需要内核的基础工具面）——回 "ok" 会被判成
      // `reply JSON has no "plugins" array` ⇒ Delegate 直接拒。
      if (body.stream !== true) {
        stubTrace.push({ round: stubRounds, who: 'aux', act: 'json-plugins', stream: body.stream });
        res.writeHead(200, { 'content-type': 'application/json' });
        res.end(JSON.stringify({
          id: 'chatcmpl-stub-aux', object: 'chat.completion', created: Math.floor(Date.now() / 1000), model: 'mock-1',
          choices: [{
            index: 0,
            message: { role: 'assistant', content: JSON.stringify({ plugins: [], reason: 'evidence stub: no extra plugins' }) },
            finish_reason: 'stop',
          }],
          usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 },
        }));
        return;
      }
      let events;
      if (isDelegate) {
        if (toolMsgs === 0) {
          stubTrace.push({ round: stubRounds, who: 'delegate', act: 'Bash' });
          events = toolCall('Bash', { command: DANGEROUS_CMD, description: '清理历史快照（取证用）' });
        } else if (toolMsgs === 1) {
          stubTrace.push({ round: stubRounds, who: 'delegate', act: 'Write' });
          events = toolCall('Write', { file_path: WRITE_PROBE, content: 'perm-card-detail evidence\n' });
        } else {
          stubTrace.push({ round: stubRounds, who: 'delegate', act: 'stop' });
          events = stop();
        }
      } else if (toolMsgs === 0) {
        stubTrace.push({ round: stubRounds, who: 'root', act: 'Delegate' });
        events = toolCall('Delegate', {
          name: 'permcard',
          task: 'PERM-EVIDENCE-BRIEF 取证任务：在本机按顺序调用两次工具。① 调用 Bash，command 用这条（逐字）：\n'
            + DANGEROUS_CMD + '\n② 再用 Write 写 ' + WRITE_PROBE + '。完成后回复 done。',
        });
      } else {
        stubTrace.push({ round: stubRounds, who: 'root', act: 'stop' });
        events = stop();
      }
      res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-cache', connection: 'keep-alive' });
      res.end(events);
    });
  });
  return new Promise((resolve) => server.listen(MOCK_PORT, '127.0.0.1', () => resolve(server)));
}

// ══ 隔离 home ══
function buildFixtureHome() {
  mkdirSync(HOME, { recursive: true });
  writeFileSync(CONFIG_PATH, JSON.stringify({
    llm: {
      providers: {
        mock: {
          baseUrl: `http://127.0.0.1:${MOCK_PORT}/v1/`,
          apiKey: 'sk-e2e-stub',
          protocol: 'openai',
          models: [{ id: 'mock-1', maxTokens: 8192, contextWindow: 128000 }],
        },
      },
    },
    // 「每次询问」档：危险 Bash 与 Write 都会出卡（本缺陷的展示面）
    safety: { defaultMode: 'confirm-edits' },
  }, null, 2));
  writeFileSync(join(HOME, 'model-presets.json'), JSON.stringify({
    defaultPreset: 'general',
    presets: { general: { name: 'general', description: '', preferred: 'mock/mock-1', fallbacks: [] } },
  }, null, 2));
}

// ══ 隔离实例（sbt run，绝不碰生产）══
const children = [];
function startGateway() {
  const sbt = spawn('sbt', ['-batch', `run --home ${HOME} --port ${GATEWAY_PORT} --no-browser`], {
    cwd: REPO,
    detached: true,
    stdio: ['ignore', 'pipe', 'pipe'],
    env: { ...process.env, NEBFLOW_GATEWAY_PORT: String(GATEWAY_PORT), SBT_OPTS: `${process.env.SBT_OPTS || ''} -Xmx3g` },
  });
  children.push(sbt);
  let buf = '';
  const pass = (tag) => (d) => {
    const s = String(d);
    buf += s;
    try { appendFileSync(join(OUT, 'gateway.log'), s); } catch { /* best effort */ }
    if (/Started|listening|error|Exception|failed/i.test(s)) log(`${tag} ${s.trim().slice(0, 240)}`);
  };
  sbt.stdout.on('data', pass('[sbt]'));
  sbt.stderr.on('data', pass('[sbt!]'));
  gatewayLog = () => buf;
  return sbt;
}
let gatewayLog = () => '';
async function waitGateway(timeoutMs = 420000) {
  const t0 = Date.now();
  while (Date.now() - t0 < timeoutMs) {
    try {
      const r = await fetch(BASE);
      if (r.status < 500) return true;
    } catch { /* not yet */ }
    await sleep(1500);
  }
  return false;
}

// ══ REST / WS 客户端（只打隔离端口）══
let TOKEN = null;
async function api(path, method = 'GET', body) {
  const res = await fetch(`${BASE}/api${path}`, {
    method,
    headers: { authorization: `Bearer ${TOKEN}`, 'content-type': 'application/json' },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text().catch(() => '');
  let json = null;
  try { json = JSON.parse(text); } catch { /* non-json */ }
  return { status: res.status, json, text };
}
const frames = [];
function connectWs() {
  return new Promise((resolve, reject) => {
    const sock = new WebSocket(`ws://127.0.0.1:${GATEWAY_PORT}/ws`, { headers: { Authorization: `Bearer ${TOKEN}` } });
    sock.addEventListener('open', () => resolve(sock));
    sock.addEventListener('error', () => reject(new Error('ws connect failed')));
    sock.addEventListener('message', (ev) => {
      try { frames.push(JSON.parse(ev.data)); } catch { /* binary */ }
    });
  });
}
async function waitFor(pred, label, timeoutMs = 180000) {
  const t0 = Date.now();
  while (Date.now() - t0 < timeoutMs) {
    const v = await pred();
    if (v) return v;
    await sleep(400);
  }
  throw new Error(`timeout waiting for ${label}`);
}

// ══ 浏览器面 ══
async function shoot(page, name, { full = false } = {}) {
  const p = join(SHOTS, name);
  await page.screenshot({ path: p, fullPage: full });
  log(`[shot] ${p}`);
  return p;
}

async function run() {
  guardIsolation();
  const mock = await startMock();
  buildFixtureHome();
  log(`[phase] stub LLM :${MOCK_PORT}; isolated home built (safety=confirm-edits)`);
  startGateway();
  if (!(await waitGateway())) throw new Error('isolated gateway never came up');
  check('A1 隔离实例起来（--home 临时根 / --port 非生产）', true, BASE);

  TOKEN = JSON.parse(readFileSync(join(HOME, 'auth.json'), 'utf8'));
  const ws = await connectWs();
  check('A2 已认证 WS 连上（前端同一通路）', true);

  // 根会话（Nebula）
  const sessions = await api('/sessions');
  const root = (sessions.json?.sessions || []).find((s) => (s.agentName || '') === 'Nebula');
  if (!root) throw new Error('no root (Nebula) session');
  log(`[root] ${root.id}`);

  // 浏览器：真前端 + 真 cookie 会话
  let chromium;
  try { ({ chromium } = await import('playwright')); } catch { ({ chromium } = await import('@playwright/test')); }
  const browser = await chromium.launch({ headless: true });
  const desktop = await browser.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 2, colorScheme: 'light' });
  const page = await desktop.newPage();
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(String(e)));
  await mintSession(page);
  await page.waitForSelector('#chat', { timeout: 30000 });
  await page.waitForTimeout(2500);
  // 明确把活动会话切到根会话（卡的落点）：前端只在「活动会话」里渲染交互卡，
  // 其它会话的帧会走 saveMsg 存档（不进 DOM）——不切会拍不到卡。
  await activateSession(page, root.id);
  await page.waitForTimeout(800);
  await shoot(page, '00-debug-initial.png');
  log(`[ui] active=${await activeSessionHint(page)}`);

  // 真 delegate：Nebula 会话发一轮 → Delegate 工具 → 派生 delegate-<agent>-<8hex>
  const turnPromise = fetch(`${BASE}/api/sessions/${root.id}/turn`, {
    method: 'POST',
    headers: { authorization: `Bearer ${TOKEN}`, 'content-type': 'application/json' },
    body: JSON.stringify({ content: 'PERM-EVIDENCE-ROOT 派一个 delegate 做取证。', timeoutSec: 180 }),
  }).then((r) => r.text()).catch((e) => `ABORTED/${e.message}`);

  const bashFrame = await waitFor(
    () => frames.find((f) => f.type === 'askPermission' && f.permDetail && f.toolName === 'Bash'),
    'askPermission(Bash) frame', 240000,
  );
  log(`[frame] Bash card sourceAgent=${bashFrame.sourceAgent} sourceSession=${bashFrame.sourceSession}`);
  check('B1 真 delegate 触发真危险 Bash 判定', bashFrame.dangerLevel >= 2,
    `dangerLevel=${bashFrame.dangerLevel} gate=${bashFrame.permDetail?.reason?.gate} rule=${bashFrame.permDetail?.reason?.ruleId}`);
  check('B2 帧携带三要素载荷（command/reason/scope）',
    !!(bashFrame.permDetail?.command && bashFrame.permDetail?.reason && bashFrame.permDetail?.scope),
    `keys=${Object.keys(bashFrame.permDetail || {}).join(',')}`);
  check('B3 来源是 delegate 会话（完整 id 可辨识）',
    String(bashFrame.sourceSession || '').startsWith('delegate-'),
    String(bashFrame.sourceSession));

  // 卡片渲染：帧已到 hub，但卡的落点会话/活动视图由产品自身路由（权限卡可能被
  // 重定锚到另一个 root），所以按产品既有三条通路把卡弄进 DOM（见 bringCard）。
  const route = await bringCard(page);
  check('B4 审批卡进入页面（通路）', route !== 'missing', route);
  if (route === 'missing') {
    const dom = await page.evaluate(() => {
      const chain = (el) => {
        const out = [];
        for (let n = el; n && n.nodeType === 1 && out.length < 6; n = n.parentElement) {
          out.push(n.id ? `#${n.id}` : n.className ? `.${String(n.className).trim().split(/\s+/).join('.')}` : n.tagName);
        }
        return out.join(' < ');
      };
      const details = [...document.querySelectorAll('.option-box')].map((el) => ({
        chain: chain(el),
        visible: !!(el.offsetParent || el.getClientRects().length),
        cmd: (el.querySelector('.perm-cmd')?.textContent || '').slice(0, 60),
      }));
      const boxes = [...document.querySelectorAll('.option-box')].map((b) => ({
        cls: b.className,
        requestId: b.dataset.requestId || '',
        visible: !!(b.offsetParent || b.getClientRects().length),
        buttons: [...b.querySelectorAll('.option-btn')].map((x) => `${x.textContent}${x.disabled ? '(disabled)' : ''}`),
        confirm: !!b.querySelector('.option-confirm'),
      }));
      return {
        rows: document.querySelectorAll('.session-item').length,
        chatRows: document.querySelectorAll('#chat .row').length,
        permRows: document.querySelectorAll('.permission-pending-box').length,
        optionBoxes: boxes.length,
        detail: details.length,
        details,
        boxes,
        barRows: document.querySelectorAll('.ask-pending-row').length,
        badge: document.getElementById('pending-asks-indicator')?.className || '',
      };
    });
    dom.frameSessionIds = frames.map((f) => `${f.toolName}@${f.sessionId || '?'}`);
    writeFileSync(join(OUT, 'debug-card-missing.json'), JSON.stringify(dom, null, 2));
    await shoot(page, '00-debug-card-missing.png', { full: true });
    log(`[ui] card missing dom=${JSON.stringify(dom)}`);
    throw new Error('approval card never entered the DOM');
  }
  await waitCard(page);
  await page.waitForFunction(() => !!document.querySelector('.option-box .perm-cmd'), null, { timeout: 30000 });
  await page.waitForTimeout(500);
  const cardState = await page.evaluate(() => {
    const cmd = document.querySelector('.option-box .perm-cmd');
    const reason = document.querySelector('.option-box .perm-reason');
    const scope = [...document.querySelectorAll('.option-box .perm-scope-item')].map((r) => r.textContent);
    const src = document.querySelector('.perm-source-badge');
    return {
      cmd: cmd ? cmd.textContent : '',
      reason: reason ? reason.textContent : '',
      scope,
      source: src ? src.textContent : '',
      toggle: !!document.querySelector('.perm-cmd-toggle'),
    };
  });
  check('B4 卡上命令正文非空（多行逐字）', cardState.cmd.trim().length > 0 && cardState.cmd.includes('\n'),
    `${cardState.cmd.split('\n').length} 行`);
  check('B5 卡上理由非空', !!cardState.reason.trim(), cardState.reason.trim());
  check('B6 卡上作用域有值', cardState.scope.length >= 2, cardState.scope.join(' | '));
  check('B7 卡上来源标识完整（非 delegate-sta 截断）',
    cardState.source.includes(String(bashFrame.sourceSession)),
    cardState.source.trim());

  // 默认折叠 = 6 行（多行正文不得撑满整屏，也不得静默截断 ⇒ scrollHeight 更大）
  const collapsed = await page.evaluate(() => {
    const pre = document.querySelector('.option-box .perm-cmd');
    const cs = getComputedStyle(pre);
    const lineH = parseFloat(cs.lineHeight) || 0;
    const pad = (parseFloat(cs.paddingTop) || 0) + (parseFloat(cs.paddingBottom) || 0);
    return { contentH: pre.clientHeight - pad, clientH: pre.clientHeight, scrollH: pre.scrollHeight, lineH, expanded: pre.classList.contains('expanded') };
  });
  check('B7b 默认折叠 6 行 + 有可展开的余量',
    !collapsed.expanded && collapsed.lineH > 0
    && collapsed.contentH <= collapsed.lineH * 6 + 1
    && collapsed.scrollH > collapsed.clientH,
    `contentH=${collapsed.contentH} 6 行=${(collapsed.lineH * 6).toFixed(1)} clientH=${collapsed.clientH} scrollH=${collapsed.scrollH}`);

  // ── 截图：1440 亮/暗 ──
  // 暗档要**两条一起**切（视觉返工批 2026-11-19）：`html[data-theme]` 只管面板 token，
  // chat.css 的暗档规则挂在 `@media (prefers-color-scheme: dark)` 上 —— 只翻 dataset
  // 会拍出一张「像亮档」的假暗图（上一版 02/05 就是这样）。
  await shoot(page, '01-desktop-1440-light.png');
  await page.emulateMedia({ colorScheme: 'dark' });
  await page.evaluate(() => { document.documentElement.dataset.theme = 'dark'; });
  await page.waitForTimeout(400);
  await shoot(page, '02-desktop-1440-dark.png');
  await page.emulateMedia({ colorScheme: 'light' });
  await page.evaluate(() => { document.documentElement.dataset.theme = 'light'; });
  await page.waitForTimeout(200);

  // ── 展开 GIF（真点击，逐帧拍卡面）──
  const gifDir = join(SHOTS, 'gif');
  mkdirSync(gifDir, { recursive: true });
  const clickToggle = () => page.evaluate(() => {
    const btn = document.querySelector('.perm-cmd-toggle');
    if (!btn) return false;
    btn.click();
    return true;
  });
  // 卡圈（含展开后的高度）作为固定裁切框：先滚到卡顶，再量一次展开态
  const rowRect = () => page.evaluate(() => {
    const detail = document.querySelector('.option-box');
    const row = detail?.closest('.row') || detail;
    if (!row) return null;
    row.scrollIntoView({ block: 'start' });
    const r = row.getBoundingClientRect();
    return { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) };
  });
  await clickToggle();
  await page.waitForTimeout(300);
  const tall = await rowRect();
  await clickToggle();
  await page.waitForTimeout(200);
  const clip = tall
    ? {
      x: Math.max(0, tall.x - 8), y: Math.max(0, tall.y - 8),
      width: Math.min(1440 - Math.max(0, tall.x - 8), tall.w + 16),
      height: Math.min(900 - Math.max(0, tall.y - 8), tall.h + 16),
    }
    : undefined;
  let gifFrame = 0;
  const snap = async () => { await page.screenshot({ path: join(gifDir, `f${String(gifFrame++).padStart(2, '0')}.png`), clip }); };
  await snap(); await page.waitForTimeout(300); await snap();
  await clickToggle();
  for (let i = 0; i < 5; i++) { await page.waitForTimeout(250); await snap(); }
  await clickToggle();
  for (let i = 0; i < 3; i++) { await page.waitForTimeout(250); await snap(); }
  log(`[gif] frames=${gifFrame} clip=${JSON.stringify(clip)}`);

  // 展开态真读数
  await clickToggle();
  await page.waitForTimeout(350);
  const expandedState = await page.evaluate(() => {
    const pre = document.querySelector('.perm-cmd');
    return { clientH: pre.clientHeight, scrollH: pre.scrollHeight, expanded: pre.classList.contains('expanded') };
  });
  check('B8 「展开全部」后全文可见（无静默截断）',
    expandedState.expanded && expandedState.clientH >= expandedState.scrollH - 2,
    `clientH=${expandedState.clientH} scrollH=${expandedState.scrollH}`);
  await shoot(page, '03-desktop-1440-expanded.png', { full: true });
  await clickToggle(); // 收起

  // ── 截图：375 手机亮/暗 ──
  const mobile = await browser.newContext({ viewport: { width: 375, height: 812 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true, colorScheme: 'light' });
  const mpage = await mobile.newPage();
  mpage.on('pageerror', (e) => pageErrors.push('mobile: ' + String(e)));
  await mintSession(mpage);
  await mpage.waitForSelector('#chat', { timeout: 30000 });
  await mpage.waitForTimeout(1500);
  await activateSession(mpage, root.id);
  const mobileRoute = await bringCard(mpage);
  check('B9a 手机 375 也拿到卡', mobileRoute !== 'missing', mobileRoute);
  await waitCard(mpage, 60000);
  await mpage.waitForTimeout(600);
  const mobileOverflow = await mpage.evaluate(() => {
    const pre = document.querySelector('.perm-cmd');
    const bubble = document.querySelector('.option-box');
    return {
      docScrollW: document.documentElement.scrollWidth,
      preW: pre ? pre.clientWidth : 0,
      bubbleW: bubble ? bubble.clientWidth : 0,
      overflow: document.documentElement.scrollWidth > 376,
    };
  });
  check('B9 手机 375 无横向溢出（卡面不撑破）', !mobileOverflow.overflow,
    `scrollWidth=${mobileOverflow.docScrollW} cmdW=${mobileOverflow.preW}`);
  await shoot(mpage, '04-mobile-375-light.png', { full: true });
  // 同桌面腿：dataset + prefers-color-scheme 两条一起切，才是真暗档。
  await mpage.emulateMedia({ colorScheme: 'dark' });
  await mpage.evaluate(() => { document.documentElement.dataset.theme = 'dark'; });
  await mpage.waitForTimeout(400);
  await shoot(mpage, '05-mobile-375-dark.png', { full: true });

  // ── 作答：拒绝 = 跳过本次调用（既有语义不变）──
  await page.evaluate(() => { document.documentElement.dataset.theme = 'light'; });
  check('C0 卡上点了「拒绝」并确认', (await answerCard(page, '拒绝')) === 'clicked');
  await page.waitForTimeout(1500);
  check('C1 拒绝后危险命令**确实没执行**（探针目录原地不动）',
    existsSync(PROBE_A) && existsSync(PROBE_B) && existsSync(PROBE_C),
    `keep-a=${existsSync(PROBE_A)} keep-b=${existsSync(PROBE_B)} keep-c=${existsSync(PROBE_C)}`);

  // ── 第二张卡（Write：真实参数摘要 + 目标路径）──
  const writeFrame = await waitFor(
    () => frames.find((f) => f.type === 'askPermission' && f.permDetail && f.toolName === 'Write'),
    'askPermission(Write) frame', 180000,
  );
  check('B10 非 Bash 工具同样带真实参数摘要 + 目标路径',
    !!writeFrame.permDetail?.command && String(writeFrame.permDetail?.scope?.target || '').includes('delegate-note.txt'),
    `target=${writeFrame.permDetail?.scope?.target} gate=${writeFrame.permDetail?.reason?.gate}`);
  const writeRoute = await bringCard(page);
  check('B10a 第二张卡（Write）进入页面', writeRoute !== 'missing', writeRoute);
  await waitCard(page);
  await page.waitForFunction(() => !!document.querySelector('.option-box .perm-scope-item'), null, { timeout: 30000 });
  await page.waitForTimeout(400);
  await shoot(page, '06-desktop-1440-write-card.png');
  // 拒绝 Write 卡（同样不落盘）
  check('C2a 第二张卡（Write）点了「拒绝」并确认', (await answerCard(page, '拒绝')) === 'clicked');
  await page.waitForTimeout(1200);
  check('C2 拒绝后 Write 卡目标文件未生成', !existsSync(WRITE_PROBE));

  // ── 真帧落成硬闸夹具 ──
  const pick = (f) => ({ name: f.toolName, toolName: f.toolName, input: f.input, permDetail: f.permDetail, sourceAgent: f.sourceAgent, sourceSession: f.sourceSession });
  mkdirSync(dirname(FIXTURE_OUT), { recursive: true });
  writeFileSync(FIXTURE_OUT, JSON.stringify({
    note: '真机取证摘出（tests/evidence-perm-card-detail.mjs）：真实 delegate 会话的 askPermission 帧，未做任何手工修补',
    capturedAt: new Date().toISOString(),
    isolatePort: GATEWAY_PORT,
    frames: [pick(bashFrame), pick(writeFrame)],
  }, null, 2));
  log(`[fixture] ${FIXTURE_OUT}`);

  // ── 落证据 ──
  writeFileSync(join(OUT, 'frames-askPermission.json'), JSON.stringify(frames.filter((f) => f.type === 'askPermission'), null, 2));
  writeFileSync(join(OUT, 'stub-trace.json'), JSON.stringify(stubTrace, null, 2));
  writeFileSync(join(OUT, 'stub-requests.json'), JSON.stringify(requestMeta, null, 2));
  writeFileSync(join(OUT, 'checks.json'), JSON.stringify({ checks, failed, pageErrors }, null, 2));
  writeFileSync(join(OUT, 'gateway-tail.log'), gatewayLog().slice(-40000));
  check('D1 浏览器零 pageerror', pageErrors.length === 0, pageErrors.slice(0, 3).join(' | '));

  // GIF 合成（真帧序列 → 动图）
  try {
    execFileSync('magick', ['-delay', '45', '-loop', '0', join(gifDir, 'f*.png'), join(SHOTS, '07-expand-toggle.gif')], { stdio: 'ignore' });
    check('D2 展开 GIF 已合成', existsSync(join(SHOTS, '07-expand-toggle.gif')), '07-expand-toggle.gif');
  } catch (e) {
    check('D2 展开 GIF 已合成', false, String(e.message).slice(0, 120));
  }

  ws.close();
  await browser.close();
  await turnPromise;
}

async function mintSession(page) {
  const mint = await fetch(`${BASE}/api/auth/console-ticket`, { method: 'POST', headers: { Authorization: `Bearer ${TOKEN}` } });
  if (!mint.ok) throw new Error(`console-ticket mint failed: HTTP ${mint.status}`);
  const { ticket } = await mint.json();
  await page.goto(`${BASE}/?ticket=${encodeURIComponent(ticket)}`, { waitUntil: 'load' });
}

/** 把活动会话切到指定 id（侧栏行点击；行不存在时退回第一个会话行）。 */
async function activateSession(page, sid) {
  const clicked = await page.evaluate((id) => {
    const row = document.querySelector(`.session-item[data-id="${id}"]`);
    if (row) { row.click(); return 'exact'; }
    const first = document.querySelector('.session-item');
    if (first) { first.click(); return 'first'; }
    return 'none';
  }, sid);
  log(`[ui] activateSession(${sid}) → ${clicked}`);
  return clicked;
}

async function activeSessionHint(page) {
  return page.evaluate(() => {
    const cur = document.querySelector('.session-item.active, .session-item.ds-side-row[aria-current="true"]');
    return {
      rows: document.querySelectorAll('.session-item').length,
      activeId: cur ? (cur.dataset.id || cur.getAttribute('data-id')) : null,
      chatRows: document.querySelectorAll('#chat .row').length,
      optionBoxes: document.querySelectorAll('.option-box').length,
    };
  });
}

/** 审批卡是否已进 DOM（真判据 = DOM 出现 + 三要素块在；不看 Playwright 的
 *  可见性口径——前端有 content-visibility 优化会误判）。 */
async function present(page, timeoutMs) {
  try {
    await page.waitForFunction(() => !!document.querySelector('.option-box .perm-cmd') && !!document.querySelector('.option-box'), null, { timeout: timeoutMs });
    return true;
  } catch {
    return false;
  }
}

/** 把审批卡弄进当前页面（三条**产品既有**通路，按序试探，不猜不轮询）：
 *   ① 帧已落在活动视图 ⇒ 直接用；
 *   ② 刷新（WS 重连 + 历史重放腿把最后一条未作答的 askPermission 重建为可作答卡）；
 *   ③ 待办条（#ask-pending-bar 的 .ask-pending-row → jumpToAsk：切到提问会话 +
 *      按 data-request-id 定位卡）。
 *  返回命中的通路名；'missing' = 三条都没成（随后 dump 现场）。 */
async function bringCard(page) {
  if (await present(page, 5000)) return 'direct';
  await page.reload({ waitUntil: 'load' });
  await page.waitForSelector('#chat', { timeout: 30000 }).catch(() => {});
  await page.waitForTimeout(1500);
  if (await present(page, 15000)) return 'reload';
  const clicked = await page.evaluate(() => {
    const row = document.querySelector('.ask-pending-row');
    if (!row) return false;
    row.click();
    return true;
  });
  log(`[ui] pending-bar row clicked=${clicked}`);
  if (clicked && await present(page, 20000)) return 'pending-bar';
  if (await present(page, 5000)) return 'pending-bar';
  return 'missing';
}

/** 等审批卡进 DOM 并滚到卡面。 */
async function waitCard(page, timeoutMs = 90000) {
  await page.waitForFunction(() => !!document.querySelector('.option-box .perm-cmd'), null, { timeout: timeoutMs });
  const rect = await page.evaluate(() => {
    const el = document.querySelector('.option-box');
    el.scrollIntoView({ block: 'center' });
    const r = el.getBoundingClientRect();
    return { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) };
  });
  log(`[ui] card box rect=${JSON.stringify(rect)}`);
  return rect;
}

/** 在卡上点一个选项并确认（DOM click：不依赖 Playwright 的可见性判据）。 */
async function answerCard(page, label) {
  const out = await page.evaluate((lbl) => {
    const boxes = [...document.querySelectorAll('.option-box')];
    if (!boxes.length) return 'no-card';
    for (const box of boxes.reverse()) { // 最新的卡在后
      const btns = [...box.querySelectorAll('.option-btn')];
      const btn = btns.find((b) => (b.dataset.label || b.textContent || '').trim() === lbl);
      if (!btn) continue;
      if (btn.disabled) return `disabled-option(${lbl}) cls=${box.className}`;
      btn.click();
      const confirm = box.querySelector('.option-confirm');
      if (!confirm) return `no-confirm cls=${box.className}`;
      if (confirm.disabled) return `disabled-confirm cls=${box.className}`;
      confirm.click();
      return 'clicked';
    }
    return `no-option(${lbl}) boxes=${boxes.length}`;
  }, label);
  log(`[ui] answerCard(${label}) → ${out}`);
  return out;
}

function cleanup() {
  for (const c of children) {
    try { process.kill(-c.pid, 'SIGTERM'); } catch { /* best effort */ }
  }
  if (process.env.KEEP_HOME !== '1') {
    try { rmSync(HOME, { recursive: true, force: true }); } catch { /* best effort */ }
  }
}

try {
  await run();
} catch (e) {
  check('Z1 取证链跑通', false, String(e && e.message).slice(0, 300));
  log(String((e && e.stack) || e));
} finally {
  cleanup();
  try { writeFileSync(join(OUT, 'stub-requests.json'), JSON.stringify(requestMeta, null, 2)); } catch { /* best effort */ }
  try { writeFileSync(join(OUT, 'stub-trace.json'), JSON.stringify(stubTrace, null, 2)); } catch { /* best effort */ }
  const summary = `${checks.map((c) => `${c.ok ? 'PASS' : 'FAIL'}  ${c.name}${c.extra ? '  — ' + c.extra : ''}`).join('\n')}\n\n${failed === 0 ? 'ALL PASS' : `${failed} CHECK(S) FAILED`}\n`;
  writeFileSync(join(OUT, '00-summary.txt'), summary);
  console.log(summary);
  process.exit(failed === 0 ? 0 : 1);
}
