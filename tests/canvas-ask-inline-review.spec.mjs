#!/usr/bin/env node
// canvas-ask-inline-review.spec.mjs — canvas-inline-diff-review web 验收
// （Cursor 式「先应用、后审阅」：ask_edit/ask_write 直接落盘 → canvas 审阅页签
// 原文档内联 diff 逐块/全量接受拒绝）。
//
// 协议面用 routeWebSocket mock 驱动（canvas-ask-edit-diff.spec.mjs 孪生骨架；
// 旧 spec 随提案 diff 卡路径退役删除），**结构断言红绿**（不卡像素）：
//   R0  页面零 pageerror（载入健康面）
//   R1  askEditApplied 帧 → 审阅页签开（.canvas-tab-pane[data-type="askreview"]）：
//       文件标签 + 全量计数、原文档全文在场（ctx 行）、就地 del/add 行、
//       逐块钮 + 全量钮在场；长未变段折叠（>14 行露头尾）
//   R2  逐块接受：点击 → askEditAccept {sessionId, hunkId} 外发、waiting 防双发；
//       resolved(done, review 余块) → 该块消失、余块重渲；接受最后一块 →
//       页签关 + 该路径 pop.readFile 重取（refreshFileTabByPath 腿）
//   R3  全量接受（⌘Enter 快捷键路）：askEditAccept 无 hunkId 外发 → resolved
//       (done, review 空) → 页签关 + readFile 重取
//   R4  全量拒绝：askEditReject 无 hunkId 外发 → resolved(done) → 页签关 +
//       readFile 重取（回写回退后重载）
//   R5  逐块拒绝：askEditReject {hunkId} 外发 → resolved(done, review 余块)
//   R6  冲突过期：resolved(outcome:error) → 显式冲突条（role=alert）呈现、绝不
//       静默；重试 → askEditAccept 重发；保留现状并关闭 → askEditDismiss 外发 →
//       resolved(done) → 页签关（dismiss 零写盘）
//   R7  askState.review 回放播种：服务端在列审阅态 → 页签重建（重载/换端面）
//   R8  提案卡退役：全程零 .ask-edit-* 节点（问答路径摘除，非隐藏）
//   R9  文本红线：行文本一律 textContent——currentText 内 HTML/script 字面量
//       不构成可执行/可成像节点
//
// 运行：node tests/canvas-ask-inline-review.spec.mjs
// 可选 env：PORT（默认 8219）、ASK_SHOTS_DIR（截图目录）。

import { chromium } from 'playwright';
import { createHash } from 'node:crypto';
import { readFileSync, mkdirSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock, ticketGuard } from './nf-ticket-mock.mjs';

/** 客户端/服务端同式的 ask 会话 id 派生（sha256(absPath) 前 8 hex，前缀 ask-）。 */
const sidOf = (absPath) => 'ask-' + createHash('sha256').update(absPath, 'utf8').digest('hex').slice(0, 8);

const HERE = dirname(fileURLToPath(import.meta.url));
// WEB_DIR 可注入（argv[2] 或 env）：红面运行 = 指向 HEAD 基线树（/tmp 搭的
// symlink 农场），绿面缺省 = 本仓工作树。
const WEB_DIR = process.env.WEB_DIR || process.argv[2] || resolve(HERE, '../src/main/resources/web');
const PORT = Number(process.env.PORT || 8219);
const BASE = `http://127.0.0.1:${PORT}`;
const SHOTS_DIR = process.env.ASK_SHOTS_DIR || resolve(HERE, `../evidence/canvas-ask-inline-review/shots`);
mkdirSync(SHOTS_DIR, { recursive: true });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ── 静态服务（serve 真实 web/ 前端源码树，canvas-stack 同款）──
const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};
const server = createServer((req, res) => {
  const url = new URL(req.url, BASE);
  let p = decodeURIComponent(url.pathname);
  if (p === '/') p = '/index.html';
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try {
    const body = readFileSync(file);
    res.writeHead(200, { 'Content-Type': MIME[file.slice(file.lastIndexOf('.'))] || 'application/octet-stream' });
    res.end(body);
  } catch {
    res.writeHead(404); res.end('not found');
  }
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));

// ── 断言器 ──
let pass = 0, fail = 0;
const results = [];
function ok(name, cond, detail = '') {
  const line = `${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`;
  results.push(line);
  console.log(line);
  if (cond) pass++; else fail++;
}

// ── ask 会话 mock 面（routeWebSocket 的 server 侧）──
/** @type {any} */ let askWs = null;
const inbound = [];                                  // page → server 帧留档（协议断言面）
function wsSend(obj) { if (askWs) askWs.send(JSON.stringify(obj)); }

async function bootPage(browser) {
  const ctx = await browser.newContext({ viewport: { width: 1600, height: 1000 }, colorScheme: 'dark' });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
    localStorage.setItem('nebflow_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
    localStorage.setItem('neblink_canvas_tabs', JSON.stringify({ v: 2, tabs: [] }));
  });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.message || e).slice(0, 300)));
  await page.route('**/api/**', (r) => r.fulfill({ json: {} }));
  await page.route(/\/api\/nf-file\?/, async (route) => {
    if (await ticketGuard(route)) return;
    const url = new URL(route.request().url());
    const p = url.searchParams.get('path') || '';
    return route.fulfill({ status: 200, contentType: 'text/markdown', body: `# ${p}\n\nmock body\n` });
  });
  await installTicketMock(page);
  await page.routeWebSocket(/\/ws/, (ws) => {
    askWs = ws;
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      inbound.push(m);
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      // pop.readFile 应答（WsFileOpsHandlers 同式）：fileContent → explorer 落
      // workspace-open-item（background）→ settleFileRefresh（_refreshing 解锁——
      // mock 不应答会把刷新面卡在 10s 安全窗里，重载腿判定就永远等不到）。
      if (m.type === 'pop.readFile' && m.path) {
        ws.send(JSON.stringify({
          type: 'fileContent', path: m.path, absPath: m.path,
          fileName: m.path.split('/').pop(), itemType: 'markdown',
          content: '# notes (refreshed)\n\nmock body\n', size: 28,
        }));
      }
      if (m.type === 'askOpen') {
        const sid = sidOf((m.file && m.file.absPath) || '');
        ws.send(JSON.stringify({
          type: 'askState',
          sessionId: sid,
          sessionKey: 'ask:' + sid.slice(4, 8),
          file: (m.file && m.file.absPath) || '',
          fileName: (m.file && m.file.name) || 'file',
          messages: [],
          review: null,
        }));
      }
    });
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
  });
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#activity-bar', { timeout: 15000 });
  await sleep(600);
  return { ctx, page, errors };
}

async function openFile(page, name) {
  const absPath = `/tmp/canvas-ask-fixture/${name}`;
  await page.evaluate(({ name, absPath }) => {
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: {
        id: 'file:' + absPath, itemType: 'markdown', title: name,
        content: `# ${name}\n\nmock body\n`, absPath, pinned: true,
      },
    }));
  }, { name, absPath });
  await page.waitForSelector(`.canvas-tab-pane[data-tab-id="file:${absPath}"]`, { state: 'visible', timeout: 10000 });
  await sleep(300);
  return absPath;
}

async function shot(page, name) {
  try { await page.screenshot({ path: join(SHOTS_DIR, name + '.png'), fullPage: false }); } catch { /* headless 沙箱截不到就算了 */ }
}

// ── 审阅面快照（结构断言面）──
// 迁移批 2026-11-19（作者裁定「手机diff 按推荐」）：审阅面渲染权移交共享组件
// （js/diffView，CodeMirror 6 + @codemirror/merge）⇒ 断言选择器随底座更新
// （旧 .nf-ar-line/.nf-ar-hunk/.nf-ar-fold 系随自研渲染器退役）。语义不变：
// 原文档全文在场、就地增删、逐块接受/拒绝、折叠、防双发、冲突条。
const reviewOf = (page, sid) => page.evaluate((tabId) => {
  const pane = document.querySelector(`.canvas-tab-pane[data-tab-id="${tabId}"]`);
  if (!pane || !pane.isConnected) return { open: false };
  const txt = (sel) => [...pane.querySelectorAll(sel)].map((l) => l.textContent || '');
  const content = pane.querySelector('.cm-content');
  const dv = pane.querySelector('.nf-dv');
  return {
    open: true,
    // canvas 特质批（2026-11-19 作者令「diff 怎么没看见 canvas 相关的特质？顶部的
    // 标题呢？」）：审阅面的顶部标题由共享组件按 canvas 头部 chrome 渲染（单源）
    // ⇒ 同义重锚 `.nf-ar-file` / `.nf-ar-counts` → `.nf-dv-title-file` /
    // `.nf-dv-title-counts` / `.nf-dv-title-status`（断言条数不减、语义不变，
    // 并补上旧名里承诺过但旧读数未覆盖的「N 块待审」状态段）。
    head: (pane.querySelector('.nf-dv-title-file') || {}).textContent || '',
    counts: (pane.querySelector('.nf-dv-title-counts') || {}).textContent || '',
    status: (pane.querySelector('.nf-dv-title-status') || {}).textContent || '',
    hasEditor: !!pane.querySelector('.cm-editor'),
    ctx: txt('.cm-line:not(.cm-changedLine)'),
    dels: txt('.cm-deletedLine'),
    adds: txt('.cm-line.cm-changedLine'),
    wordCount: pane.querySelectorAll('.cm-changedText').length,
    chunks: pane.querySelectorAll('.cm-deletedChunk').length,
    chunkAccept: pane.querySelectorAll('.nf-dv-chunkbtn-accept').length,
    chunkReject: pane.querySelectorAll('.nf-dv-chunkbtn-reject').length,
    // 同义重锚（作者令 2026-11-19：审阅动作区单源落共享组件 js/diffView 的 review
    // 模式）：全量钮由头部 `.nf-ar-accept-all` / `.nf-ar-reject-all`（已删净）迁到
    // 组件内 `.nf-dv-accept-all` / `.nf-dv-reject-all`。语义与断言强度不变，且补齐
    // 「在场」显式断言（旧写法用 `!disabled` 兼判存在，元素缺失时也恒真 = 隐性弱化）。
    acceptAllPresent: !!pane.querySelector('.nf-dv-accept-all'),
    rejectAllPresent: !!pane.querySelector('.nf-dv-reject-all'),
    acceptAllDisabled: (pane.querySelector('.nf-dv-accept-all') || {}).disabled === true,
    rejectAllDisabled: (pane.querySelector('.nf-dv-reject-all') || {}).disabled === true,
    conflict: !!pane.querySelector('.nf-ar-conflict[role="alert"]'),
    folds: pane.querySelectorAll('.cm-collapsedLines').length,
    waiting: !!(dv && dv.getAttribute('data-waiting') === '1'),
    text: content ? (content.textContent || '') : '',
  };
}, `askreview:${sid}`);

/** applied 帧构造（服务端 AskEditReviews 的 wire 形：base/current/hunks 单源）。 */
let changeSeq = 0;
function appliedFrame(sid, abs, fileName, baseText, currentText, hunks) {
  changeSeq += 1;
  return {
    type: 'askEditApplied', sessionId: sid, changeId: 'c' + changeSeq,
    path: abs, fileName, baseText, currentText,
    hunks, count: 1, updatedAt: Date.now(),
  };
}

// 两块几何（后端 AskEditReviewSpec twoBlock 同款：首行政 + 尾部追加，5 行隔离带
// > HunkMergeGap(2) ⇒ 两独立块）。
const TWO_BASE = 'alpha\nbeta\ngamma\ngap1\ngap2\ngap3\n';
const TWO_CUR = 'ALPHA\nbeta\ngamma\ngap1\ngap2\ngap3\ndelta\n';
const TWO_HUNKS = [
  { id: 'h0', oldStart: 0, oldCount: 1, newStart: 0, newCount: 1, added: 1, removed: 1 },
  { id: 'h1', oldStart: 6, oldCount: 1, newStart: 6, newCount: 2, added: 2, removed: 1 },
];

// ═══════════════════════════════════════════════════════════════════════════
const browser = await chromium.launch();
try {
  const { page, errors } = await bootPage(browser);
  const abs = await openFile(page, 'notes.md');
  const sid = sidOf(abs);
  // 画布刷新冷却（FILE_REFRESH_COOLDOWN=2000；开页签渲染即 _lastRefreshAt 时戳）：
  // 冷却窗外驱动后续 resolve，重载腿判定才确定性（scheduleFileRefresh 冷却窗内
  // 静默 return 不重试——canvas.js 既定守卫语义）。
  await sleep(2300);
  await page.click('.ask-fab');                       // 开 dock（askOpen → 建会话账）
  await sleep(500);

  // R0 载入健康
  ok('R0 页面零 pageerror', errors.length === 0, errors.slice(0, 2).join(' | '));

  // ── R1 askEditApplied → 审阅页签结构 ─────────────────────────────────────
  // 几何 = 服务端 hunksOf 真实产物：beta → [BETA, DELTA] 一个 delta（old(1,1) →
  // new(1,2)），前后 alpha/gamma 为 ctx。
  const R1_BASE = 'alpha\nbeta\ngamma\n';
  const R1_CUR = 'alpha\nBETA\nDELTA\ngamma\n';
  wsSend(appliedFrame(sid, abs, 'notes.md', R1_BASE, R1_CUR, [
    { id: 'h0', oldStart: 1, oldCount: 1, newStart: 1, newCount: 2, added: 2, removed: 1 },
  ]));
  await page.waitForSelector(`.canvas-tab-pane[data-tab-id="askreview:${sid}"]`, { timeout: 5000 });
  await sleep(200);
  let rv = await reviewOf(page, sid);
  ok('R1a 审阅页签开：canvas 头部标题（文件名 notes.md + 全量计数 +2 −1 + 审阅状态 1 块待审）',
    rv.open && rv.head.includes('notes.md') && rv.counts.includes('+2') && rv.counts.includes('−1')
      && rv.status.includes('1 块待审'),
    JSON.stringify({ head: rv.head, counts: rv.counts, status: rv.status }));
  ok('R1b 原文档全文在场（ctx = alpha/gamma 基线行）',
    rv.open && rv.ctx.includes('alpha') && rv.ctx.includes('gamma'), JSON.stringify(rv.ctx));
  ok('R1c 就地增删：del 1（− beta）、add 2（+ BETA / + DELTA）',
    rv.open && rv.dels.length === 1 && rv.dels[0].includes('beta')
      && rv.adds.length === 2 && rv.adds.some((t) => t.includes('BETA')) && rv.adds.some((t) => t.includes('DELTA')),
    JSON.stringify({ dels: rv.dels, adds: rv.adds }));
  ok('R1d 动作面在场：逐块接受/拒绝钮（共享组件改壳）+ 全量接受/拒绝钮（均在组件内且可点）',
    rv.open && rv.chunkAccept === 1 && rv.chunkReject === 1
      && rv.acceptAllPresent && rv.rejectAllPresent && !rv.acceptAllDisabled && !rv.rejectAllDisabled,
    JSON.stringify({ accept: rv.chunkAccept, reject: rv.chunkReject, allPresent: rv.acceptAllPresent, all: rv.acceptAllDisabled }));

  // ── R1e 长未变段折叠（原文档全文的可读性面，非硬截断）──────────────────
  const LONG_BASE = Array.from({ length: 40 }, (_, i) => `line-${i}`).join('\n') + '\n';
  const LONG_CUR = LONG_BASE.replace('line-20\n', 'LINE-TWENTY\n');
  wsSend(appliedFrame(sid, abs, 'notes.md', LONG_BASE, LONG_CUR, [
    { id: 'h0', oldStart: 20, oldCount: 1, newStart: 20, newCount: 1, added: 1, removed: 1 },
  ]));
  await sleep(300);
  rv = await reviewOf(page, sid);
  ok('R1e1 长未变段折叠：头尾两段各 >14 行 → 2 条折叠带在场',
    rv.open && rv.folds === 2, JSON.stringify(rv.folds));
  await page.locator('.cm-collapsedLines').first().click();
  await sleep(200);
  rv = await reviewOf(page, sid);
  ok('R1e2 点击折叠带 → 中间行展开（折叠带数减少）',
    rv.open && rv.folds < 2, `剩余折叠带 ${rv.folds}（点前 2）`);
  await shot(page, 'r1-applied');

  // ── R2 逐块接受 → 余块重渲 → 末块清账收页签 ─────────────────────────────
  wsSend(appliedFrame(sid, abs, 'notes.md', TWO_BASE, TWO_CUR, TWO_HUNKS));
  await sleep(300);
  const readsOf = () => inbound.filter((m) => m.type === 'pop.readFile' && m.path === abs).length;
  await page.locator('.nf-dv-chunkbtn-accept').first().click();
  await sleep(400);
  const hunkAccept = inbound.find((m) => m.type === 'askEditAccept' && m.hunkId === 'h0');
  ok('R2a 逐块接受 → askEditAccept {sessionId, hunkId:h0} 外发',
    !!hunkAccept && hunkAccept.sessionId === sid, JSON.stringify(hunkAccept));
  rv = await reviewOf(page, sid);
  ok('R2b 防双发：waiting 面在场 + 全量钮 disabled',
    rv.open && rv.waiting && rv.acceptAllDisabled && rv.rejectAllDisabled,
    JSON.stringify({ waiting: rv.waiting, all: rv.acceptAllDisabled }));
  // 服务端应答：h0 已审清出（基线前移），仅余 h1。
  wsSend({
    type: 'askEditResolved', sessionId: sid, action: 'accept', outcome: 'done',
    review: {
      path: abs, fileName: 'notes.md', baseText: 'ALPHA\nbeta\ngamma\ngap1\ngap2\ngap3\n',
      currentText: TWO_CUR,
      hunks: [{ id: 'h1', oldStart: 6, oldCount: 1, newStart: 6, newCount: 2, added: 2, removed: 1 }],
      count: 1, updatedAt: Date.now(),
    },
  });
  await sleep(400);
  rv = await reviewOf(page, sid);
  ok('R2c resolved(done, 余块) → 只剩 1 块待审、waiting 解锁',
    rv.open && rv.chunks === 1 && !rv.acceptAllDisabled,
    JSON.stringify({ chunks: rv.chunks, all: rv.acceptAllDisabled }));
  await shot(page, 'r2-hunk-accepted');
  // 接受最后一块 → 清账：页签关 + 文件页签按路径重载。
  const readsBeforeClear = readsOf();
  await page.locator('.nf-dv-chunkbtn-accept').first().click();
  await sleep(400);
  const lastAccept = inbound.filter((m) => m.type === 'askEditAccept').pop();
  ok('R2d 末块接受 → hunkId=h1 外发（余块映射）',
    !!lastAccept && lastAccept.hunkId === 'h1', JSON.stringify(lastAccept));
  wsSend({ type: 'askEditResolved', sessionId: sid, action: 'accept', outcome: 'done', review: null });
  await page.waitForSelector(`.canvas-tab-pane[data-tab-id="askreview:${sid}"]`, { state: 'detached', timeout: 5000 });
  let cleared = false;
  for (let i = 0; i < 40 && !cleared; i++) { await sleep(100); cleared = readsOf() > readsBeforeClear; }
  ok('R2e 末块接受 → 清账收页签 + 该路径 pop.readFile 重取（refreshFileTabByPath 腿）',
    cleared, `pop.readFile ${readsBeforeClear} → ${readsOf()}`);

  // ── R3 全量接受（⌘Enter 快捷键路）──────────────────────────────────────
  await sleep(2100); // 刷新冷却窗外（上一腿刚重载过）
  wsSend(appliedFrame(sid, abs, 'notes.md', TWO_BASE, TWO_CUR, TWO_HUNKS));
  await page.waitForSelector(`.canvas-tab-pane[data-tab-id="askreview:${sid}"]`, { timeout: 5000 });
  await sleep(200);
  // R3a0 全量接受·**点击路**（组件内全量钮）：点击 → askEditAccept 无 hunkId 外发
  //      （同一动作面、同一语义，非第二套协议）→ resolved(done, 余块) → 审阅保持。
  await page.click('.nf-dv-accept-all');
  await sleep(300);
  const clickAllAccept = inbound.filter((m) => m.type === 'askEditAccept').pop();
  ok('R3a0 点击「全部接受」（组件内全量钮）→ askEditAccept 无 hunkId 外发（既有全量语义）',
    !!clickAllAccept && clickAllAccept.sessionId === sid && !('hunkId' in clickAllAccept),
    JSON.stringify(clickAllAccept));
  wsSend({
    type: 'askEditResolved', sessionId: sid, action: 'accept', outcome: 'done',
    review: {
      path: abs, fileName: 'notes.md', baseText: TWO_BASE, currentText: TWO_CUR,
      hunks: TWO_HUNKS, count: 1, updatedAt: Date.now(),
    },
  });
  await sleep(400);
  const readsBeforeAll = readsOf();
  await page.keyboard.press('Meta+Enter');
  await sleep(300);
  const allAccept = inbound.filter((m) => m.type === 'askEditAccept').pop();
  ok('R3a ⌘Enter（审阅页签激活）→ askEditAccept 无 hunkId 外发（=全部接受）',
    !!allAccept && allAccept.sessionId === sid && !('hunkId' in allAccept), JSON.stringify(allAccept));
  rv = await reviewOf(page, sid);
  ok('R3b waiting 面：全量钮 disabled（防双发，等 resolved 终态）',
    rv.open && rv.acceptAllDisabled && rv.rejectAllDisabled, JSON.stringify(rv.acceptAllDisabled));
  wsSend({ type: 'askEditResolved', sessionId: sid, action: 'accept', outcome: 'done', review: null });
  await page.waitForSelector(`.canvas-tab-pane[data-tab-id="askreview:${sid}"]`, { state: 'detached', timeout: 5000 });
  let refetched3 = false;
  for (let i = 0; i < 40 && !refetched3; i++) { await sleep(100); refetched3 = readsOf() > readsBeforeAll; }
  ok('R3c resolved(done, review 空) → 页签关 + readFile 重取', refetched3, `pop.readFile ${readsBeforeAll} → ${readsOf()}`);
  await shot(page, 'r3-accept-all-cleared');

  // ── R4 全量拒绝 ─────────────────────────────────────────────────────────
  await sleep(2100);
  wsSend(appliedFrame(sid, abs, 'notes.md', TWO_BASE, TWO_CUR, TWO_HUNKS));
  await page.waitForSelector(`.canvas-tab-pane[data-tab-id="askreview:${sid}"]`, { timeout: 5000 });
  await sleep(200);
  const readsBeforeRejectAll = readsOf();
  await page.click('.nf-dv-reject-all');
  await sleep(300);
  const rejectAll = inbound.filter((m) => m.type === 'askEditReject').pop();
  ok('R4a 全量拒绝 → askEditReject 无 hunkId 外发',
    !!rejectAll && rejectAll.sessionId === sid && !('hunkId' in rejectAll), JSON.stringify(rejectAll));
  wsSend({ type: 'askEditResolved', sessionId: sid, action: 'reject', outcome: 'done', review: null });
  await page.waitForSelector(`.canvas-tab-pane[data-tab-id="askreview:${sid}"]`, { state: 'detached', timeout: 5000 });
  let refetched4 = false;
  for (let i = 0; i < 40 && !refetched4; i++) { await sleep(100); refetched4 = readsOf() > readsBeforeRejectAll; }
  ok('R4b 整体回退 → 页签关 + readFile 重载（回写后重取）', refetched4, `pop.readFile ${readsBeforeRejectAll} → ${readsOf()}`);

  // ── R5 逐块拒绝 ─────────────────────────────────────────────────────────
  wsSend(appliedFrame(sid, abs, 'notes.md', TWO_BASE, TWO_CUR, TWO_HUNKS));
  await page.waitForSelector(`.canvas-tab-pane[data-tab-id="askreview:${sid}"]`, { timeout: 5000 });
  await sleep(200);
  await page.locator('.nf-dv-chunkbtn-reject').first().click();
  await sleep(400);
  const hunkReject = inbound.find((m) => m.type === 'askEditReject' && m.hunkId === 'h0');
  ok('R5a 逐块拒绝 → askEditReject {sessionId, hunkId:h0} 外发',
    !!hunkReject && hunkReject.sessionId === sid, JSON.stringify(hunkReject));
  wsSend({
    type: 'askEditResolved', sessionId: sid, action: 'reject', outcome: 'done',
    review: {
      path: abs, fileName: 'notes.md', baseText: TWO_BASE,
      currentText: 'alpha\nbeta\ngamma\ngap1\ngap2\ngap3\ndelta\n',
      hunks: [{ id: 'h1', oldStart: 6, oldCount: 1, newStart: 6, newCount: 2, added: 2, removed: 1 }],
      count: 1, updatedAt: Date.now(),
    },
  });
  await sleep(400);
  rv = await reviewOf(page, sid);
  ok('R5b resolved(done, 余块) → 只剩 1 块（该块回退后基线前移）',
    rv.open && rv.chunks === 1, JSON.stringify({ chunks: rv.chunks }));
  await shot(page, 'r5-hunk-rejected');

  // ── R6 冲突过期（显式提示，绝不静默覆盖）─────────────────────────────────
  await page.click('.nf-dv-accept-all');
  await sleep(300);
  wsSend({
    type: 'askEditResolved', sessionId: sid, action: 'accept', outcome: 'error',
    error: 'stale: file changed during review — retry, or dismiss to keep the on-disk state',
    review: {
      path: abs, fileName: 'notes.md', baseText: TWO_BASE, currentText: TWO_CUR,
      hunks: TWO_HUNKS, count: 1, updatedAt: Date.now(),
    },
  });
  await sleep(300);
  rv = await reviewOf(page, sid);
  ok('R6a resolved(error) → 显式冲突条在场（role=alert），审阅块仍待审（不静默清账）',
    rv.open && rv.conflict && rv.chunks === 2, JSON.stringify({ conflict: rv.conflict, chunks: rv.chunks }));
  await shot(page, 'r6-conflict');
  await page.click('.nf-ar-retry');
  await sleep(300);
  const retryFrame = inbound.filter((m) => m.type === 'askEditAccept').pop();
  ok('R6b 重试 → askEditAccept 重发（同一动作面）',
    !!retryFrame && retryFrame.sessionId === sid, JSON.stringify(retryFrame));
  // 再次冲突 → 走逃生口：保留现状并关闭（dismiss 零写盘）。
  wsSend({
    type: 'askEditResolved', sessionId: sid, action: 'accept', outcome: 'error',
    error: 'stale: file changed during review — retry, or dismiss to keep the on-disk state',
    review: {
      path: abs, fileName: 'notes.md', baseText: TWO_BASE, currentText: TWO_CUR,
      hunks: TWO_HUNKS, count: 1, updatedAt: Date.now(),
    },
  });
  await sleep(300);
  await page.click('.nf-ar-dismiss');
  await sleep(300);
  const dismissFrame = inbound.find((m) => m.type === 'askEditDismiss');
  ok('R6c 保留现状并关闭 → askEditDismiss {sessionId} 外发（零写盘清账）',
    !!dismissFrame && dismissFrame.sessionId === sid, JSON.stringify(dismissFrame));
  wsSend({ type: 'askEditResolved', sessionId: sid, action: 'dismiss', outcome: 'done', review: null });
  // R6d 判据 = waitForSelector detached（超时即抛 → 收口为决定性 FAIL； dismiss
  // 零写盘，文件页签重载腿按需呈现盘上现状——不另断言读数）。
  await page.waitForSelector(`.canvas-tab-pane[data-tab-id="askreview:${sid}"]`, { state: 'detached', timeout: 5000 });
  ok('R6d dismiss 清账 → 页签关（零写盘逃生口收口）', true);

  // ── R7 askState.review 回放播种 ─────────────────────────────────────────
  wsSend({
    type: 'askState', sessionId: sid, sessionKey: 'ask:' + sid.slice(4, 8), file: abs,
    fileName: 'notes.md', messages: [],
    review: {
      path: abs, fileName: 'notes.md', baseText: R1_BASE, currentText: R1_CUR,
      hunks: [{ id: 'h0', oldStart: 1, oldCount: 1, newStart: 1, newCount: 2, added: 2, removed: 1 }],
      count: 1, updatedAt: Date.now(),
    },
  });
  await page.waitForSelector(`.canvas-tab-pane[data-tab-id="askreview:${sid}"]`, { timeout: 5000 });
  rv = await reviewOf(page, sid);
  ok('R7 askState.review 播种 → 审阅页签重建（重载/换端回放面），块待审',
    rv.open && rv.chunkAccept === 1 && !rv.waiting, JSON.stringify({ accept: rv.chunkAccept, waiting: rv.waiting }));
  // 收尾清账（下一断言面干净）。清账腿会排一个 300ms 的文件页签刷新（防抖），
  // 等它落地再进 R8/R9——避免刷新腿与下一个 applied 帧的页签生命周期竞态。
  wsSend({ type: 'askEditResolved', sessionId: sid, action: 'dismiss', outcome: 'done', review: null });
  await sleep(900);

  // ── R8 提案卡退役（问答路径摘除，非隐藏）────────────────────────────────
  const retired = await page.evaluate(() => ({
    cards: document.querySelectorAll('.ask-edit-card').length,
    anyAskEdit: document.querySelectorAll('[class*="ask-edit"]').length,
    askEditCss: [...document.styleSheets].some((ss) => {
      try { return [...ss.cssRules].some((r) => r.selectorText && r.selectorText.includes('ask-edit')); }
      catch { return false; }
    }),
  }));
  ok('R8 提案 diff 卡退役：DOM 零 .ask-edit-* 节点、样式表零 ask-edit 规则',
    retired.cards === 0 && retired.anyAskEdit === 0 && !retired.askEditCss, JSON.stringify(retired));

  // ── R9 文本红线（行文本 textContent 面）─────────────────────────────────
  const hostile = '<img src=x onerror=window.__arXss=1><script>window.__arXss=1<\/script>';
  wsSend(appliedFrame(sid, abs, 'notes.md', 'safe\n', hostile + '\n', [
    { id: 'h0', oldStart: 0, oldCount: 1, newStart: 0, newCount: 1, added: 1, removed: 1 },
  ]));
  await page.waitForSelector(`.canvas-tab-pane[data-tab-id="askreview:${sid}"]`, { timeout: 5000 });
  await sleep(200);
  const safety = await page.evaluate((tabId) => {
    const pane = document.querySelector(`.canvas-tab-pane[data-tab-id="${tabId}"]`);
    if (!pane) return { found: false };
    return {
      found: true,
      imgs: pane.querySelectorAll('img').length,
      scripts: pane.querySelectorAll('script').length,
      literal: (pane.textContent || '').includes('<img src=x'),
      fired: window.__arXss === 1,
    };
  }, `askreview:${sid}`);
  ok('R9 行文本红线：HTML/script 字面量不构成节点、不执行（textContent 面）',
    safety.found && safety.imgs === 0 && safety.scripts === 0 && safety.literal && !safety.fired,
    JSON.stringify(safety));

  await sleep(200);
  ok('R0b 收尾零 pageerror（全程含 applied/resolved/askState 驱动 + 多次点击/键盘）',
    errors.length === 0, errors.slice(0, 2).join(' | '));
} catch (e) {
  // 红面驱动中断（基线 web/ 无审阅页签实现 ⇒ waitForSelector 超时等）：记一条
  // 决定性 FAIL 而非裸栈崩溃——红绿两态都以汇总行 + 退出码收口。
  ok('SPEC 驱动中断（红面：applied 帧未成页签/关键元素缺失）', false, String((e && e.message) || e).slice(0, 240));
} finally {
  await browser.close();
  server.close();
}

console.log(`\n${fail === 0 ? '✅' : '❌'} canvas-ask-inline-review: ${pass} passed, ${fail} failed`);
process.exit(fail === 0 ? 0 : 1);
